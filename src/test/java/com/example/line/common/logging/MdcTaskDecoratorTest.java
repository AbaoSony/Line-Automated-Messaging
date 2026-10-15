package com.example.line.common.logging;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;

/** 验证异步提交快照包含链路、事件和完整用户标识，并在复用线程和异常退出时恢复上下文。 */
class MdcTaskDecoratorTest {
    @AfterEach
    void clearContext() { MDC.clear(); }

    @Test
    void capturesSubmitTimeSnapshotAndRestoresWorkerContextAfterException() {
        MDC.setContextMap(Map.of("trace_id", "fictional-trace", "webhook_event_id", "fictional-event", "user_id", "Ufictional-user"));
        var task = new MdcTaskDecorator().decorate(() -> {
            assertThat(MDC.get("trace_id")).isEqualTo("fictional-trace");
            assertThat(MDC.get("webhook_event_id")).isEqualTo("fictional-event");
            assertThat(MDC.get("user_id")).isEqualTo("Ufictional-user");
            throw new IllegalStateException("Simulated task failure");
        });
        MDC.setContextMap(Map.of("worker", "original"));
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("worker", "original"));
    }

    @Test
    void reusedWorkerDoesNotKeepPreviousUserAndBackgroundTaskGetsNewTrace() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var first = new AtomicReference<Map<String, String>>();
        var second = new AtomicReference<Map<String, String>>();
        var finished = new CountDownLatch(2);
        var decorator = new MdcTaskDecorator();
        try {
            MDC.setContextMap(Map.of("trace_id", "fictional-request-trace", "user_id", "Ufictional-first-user", "webhook_event_id", "fictional-first-event"));
            pool.execute(decorator.decorate(() -> { first.set(MDC.getCopyOfContextMap()); finished.countDown(); }));
            MDC.clear();
            pool.execute(decorator.decorate(() -> { second.set(MDC.getCopyOfContextMap()); finished.countDown(); }));
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.get()).containsEntry("user_id", "Ufictional-first-user");
            assertThat(UUID.fromString(second.get().get("trace_id"))).isNotNull();
            assertThat(second.get()).doesNotContainKeys("user_id", "webhook_event_id");
            assertThat(second.get().get("trace_id")).isNotEqualTo(first.get().get("trace_id"));
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
