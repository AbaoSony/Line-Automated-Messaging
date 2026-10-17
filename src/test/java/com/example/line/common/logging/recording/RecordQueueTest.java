package com.example.line.common.logging.recording;

import com.example.line.common.logging.LogEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;

/** 验证队列满不等待、一次重试、JSON 兜底、审计失败隔离和停机刷完所有已接收记录。 */
@ExtendWith(OutputCaptureExtension.class)
class RecordQueueTest {
    @Test void auditRejectedByDeletionBarrierReportsPersistenceFailure(CapturedOutput output) {
        var tracker=new com.example.line.common.execution.UserWorkTracker();
        var meters=new SimpleMeterRegistry();
        var queue=new RecordQueue(batch -> fail("Blocked audit must not be written"),properties(2,1),meters,tracker);
        queue.start();
        try(var barrier=tracker.block("Ufictional-queue-user")) {
            assertThat(queue.offer(true,fields())).isFalse();
            assertThat(meters.get("persist.failed").tag("operation","audit_log").counter().count()).isEqualTo(1);
            assertThat(output).contains("persist.failed","user_work_blocked");
        }finally{queue.close();}
    }
    private static RecorderProperties properties(int capacity, int batch) {
        return new RecorderProperties(capacity, batch, Duration.ofMillis(10), 2, 250, 1);
    }

    @Test
    void fullQueueDropsImmediatelyWhileDatabaseWriterIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var written = new CopyOnWriteArrayList<RecordedEntry>();
        var meters = new SimpleMeterRegistry();
        var queue = new RecordQueue(batch -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            written.addAll(batch);
        }, properties(1, 1), meters);
        queue.start();
        try {
            assertThat(queue.offer(false, fields())).isTrue();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.offer(false, fields())).isTrue();
            assertThat(queue.isIdle()).isFalse();
            long started = System.nanoTime();
            assertThat(queue.offer(false, fields())).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
            assertThat(meters.get("log.dropped").counter().count()).isEqualTo(1);
        } finally {
            release.countDown();
            queue.close();
        }
        assertThat(written).hasSize(2);
        assertThat(queue.isIdle()).isTrue();
    }

    @Test
    void failedWriteRetriesOnceThenFallsBackToJsonWithoutEnqueueingFailure(CapturedOutput output) throws Exception {
        var calls = new AtomicInteger();
        var ids = new CopyOnWriteArrayList<java.util.UUID>();
        var meters = new SimpleMeterRegistry();
        var queue = new RecordQueue(batch -> {
            calls.incrementAndGet();
            batch.forEach(entry -> ids.add(entry.id()));
            throw new IllegalStateException("fake-private-database-password");
        }, properties(2, 2), meters);
        queue.start();
        assertThat(queue.offer(false, fields())).isTrue();
        queue.close();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(meters.get("persist.failed").tag("operation", "event_log").counter().count()).isEqualTo(1);
        assertThat(output).contains("persist.failed", "write_failed_after_retry", "完整虚构消息").doesNotContain("fake-private-database-password");
        var fallback = output.getOut().lines().filter(line -> line.startsWith("{") && line.contains("persistence_fallback")).toList();
        assertThat(fallback).hasSize(1);
        var json = new ObjectMapper().readTree(fallback.get(0));
        assertThat(json.path("trace_id").asText()).isEqualTo("fictional-queue-trace");
        assertThat(json.path("user_id").asText()).isEqualTo("Ufictional-queue-user");
        assertThat(json.path("detail").path("content").path("text").asText()).isEqualTo("完整虚构消息");
    }

    @Test
    void transientFailureUsesSameRecordAndSucceedsOnSingleRetry(CapturedOutput output) {
        var calls = new AtomicInteger();
        var written = new CopyOnWriteArrayList<RecordedEntry>();
        var queue = new RecordQueue(batch -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("fake-first-failure");
            written.addAll(batch);
        }, properties(4, 4), new SimpleMeterRegistry());
        queue.start();
        queue.offer(false, fields());
        queue.close();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(written).hasSize(1);
        assertThat(output).doesNotContain("persistence_fallback", "fake-first-failure");
    }

    @Test
    void shutdownFlushesAllBatchesAndRejectsLaterAuditWithoutThrowing(CapturedOutput output) {
        var written = new CopyOnWriteArrayList<RecordedEntry>();
        var meters = new SimpleMeterRegistry();
        var queue = new RecordQueue(written::addAll, properties(100, 3), meters);
        queue.start();
        for (int index = 0; index < 25; index++) assertThat(queue.offer(index % 2 == 0, fields())).isTrue();
        queue.close();
        assertThat(written).hasSize(25);
        assertThat(queue.isIdle()).isTrue();
        assertThat(queue.offer(true, fields())).isFalse();
        assertThat(meters.get("persist.failed").tag("operation", "audit_log").counter().count()).isEqualTo(1);
        assertThat(output).contains("persist.failed", "queue_unavailable");
    }

    @Test
    void detailWhitelistRetainsFullBusinessDataAndDropsStackAndCredentials() {
        var snapshot = RecordData.snapshot(Map.of("trace_id", "fictional-trace", "user_id", "Ufictional-user",
                "content", Map.of("text", "虚构完整用户文本", "quoteToken", "fake-private-quote-token",
                        "nested", Map.of("api_key", "fake-private-key", "caption", "完整元数据")),
                "stack_trace", List.of("private-frame"), "unexpected", "private-unknown", "replyToken", "fake-private-reply-token"));
        assertThat(snapshot).containsEntry("user_id", "Ufictional-user").doesNotContainKeys("stack_trace", "unexpected", "replyToken");
        assertThat(snapshot.get("detail").toString()).contains("虚构完整用户文本", "完整元数据")
                .doesNotContain("private-frame", "fake-private", "private-unknown");
    }

    private static Map<String, Object> fields() {
        return RecordData.snapshot(Map.of("event", LogEvents.USER_MESSAGE_RECEIVED, "trace_id", "fictional-queue-trace",
                "user_id", "Ufictional-queue-user", "content", Map.of("text", "完整虚构消息")));
    }

    @Test void nextDeletionWaitsForPreviousDeletionAuditActualExitBeforeRemovingHistoricalRecords()throws Exception{
        var tracker=new com.example.line.common.execution.UserWorkTracker();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var queue=new RecordQueue(batch -> {entered.countDown();try{assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException failure){throw new AssertionError(failure);}},properties(10,1),new SimpleMeterRegistry(),tracker);
        queue.start();String user="Ufictional-deletion-audit";
        try{
            try(var first=tracker.block(user)){
                first.await(Duration.ofMillis(20));first.committed();
                assertThat(queue.offer(true,RecordData.snapshot(Map.of("event",LogEvents.USER_DATA_DELETED,"user_id",user,
                        "target_user_id",user,"trace_id","fictional-audit-trace","outcome","SUCCESS")))).isTrue();
            }
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            try(var second=tracker.block(user)){
                assertThatThrownBy(() -> second.await(Duration.ofMillis(30))).isInstanceOf(IllegalStateException.class);
                release.countDown();second.await(Duration.ofSeconds(1));
            }
        }finally{release.countDown();queue.close();}
    }
}
