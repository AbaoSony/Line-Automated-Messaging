package com.example.line.ai.coordination;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** 验证定时/队列拒绝、成员容量及模式互斥，不以取消排队 Future 冒充实际调用已经结束。 */
class TurnResourceTest extends TurnTestSupport {
    @Test
    void timerRejectionAndWorkerRejectionCompleteAdmissionsWithoutLeaksOrExternalWork() {
        TurnWork forbidden = (ticket, snapshot, deadline) -> fail("Rejected work executed");
        coordinator = new TurnCoordinator(ai(), aggregation(), decision(), Duration.ofSeconds(40), executor,
                (at, action) -> { throw new RejectedExecutionException(); }, clock::get, forbidden);
        var rejectedTimer = admit("user", 1);
        assertThat(result(rejectedTimer).state()).isEqualTo(TurnState.REJECTED); assertThat(coordinator.isIdle()).isTrue();
        coordinator = new TurnCoordinator(ai(), aggregation(), decision(), Duration.ofSeconds(40),
                action -> { throw new RejectedExecutionException(); }, timer, clock::get, forbidden);
        var rejectedWorker = admit("user", 2);
        assertThat(result(rejectedWorker).state()).isEqualTo(TurnState.REJECTED);
        assertThat(coordinator.isIdle()).isTrue(); assertThat(timer.pending()).isZero();
    }

    @Test
    void repeatedInputKeepsAtMostThreeTimersPerTurnAndStopRemovesQueuedJobsWithoutRunningThem() {
        setup((ticket, snapshot, deadline) -> fail("Stopped job executed"));
        var accepted = new ArrayList<TurnAdmission>();
        for (long sequence = 1; sequence <= 16; sequence++) {
            accepted.add(admit("user", sequence));
            assertThat(timer.pending()).isLessThanOrEqualTo(6);
            assertThat(coordinator.watermark().bufferedMembers()).isLessThanOrEqualTo(16);
        }
        assertThat(result(admit("user", 17)).reason()).isEqualTo(TurnResult.Reason.CAPACITY);
        coordinator.stop();
        assertThat(coordinator.isIdle()).isTrue(); assertThat(timer.pending()).isZero();
        executor.drain();
        for (var admission : accepted) assertThat(result(admission).state()).isEqualTo(TurnState.CANCELLED);
        timer.cancelledActions().forEach(Runnable::run); assertThat(coordinator.isIdle()).isTrue();
    }

    @Test
    void expiredRawDeadlineNeverStartsDecisionAndGlobalBufferRejectsWithoutEviction() {
        setup((ticket, snapshot, deadline) -> fail("Expired or cancelled job executed"));
        var original = deadline(); clock.set(Duration.ofSeconds(45).toNanos());
        assertThat(result(coordinator.admit(member("user", 1), original)).state()).isEqualTo(TurnState.EXPIRED);
        assertThat(executor.pending()).isZero();
        coordinator = new TurnCoordinator(ai(), aggregation("max-members", "1", "max-buffered-members", "1"), decision(),
                Duration.ofSeconds(40), executor, timer, clock::get, (ticket, snapshot, deadline) -> fail("Cancelled queued job executed"));
        var held = admit("A", 2);
        assertThat(coordinator.watermark().bufferedMembers()).isEqualTo(1);
        assertThat(result(admit("B", 3)).reason()).isEqualTo(TurnResult.Reason.CAPACITY);
        coordinator.cancel("A"); assertThat(result(held).state()).isEqualTo(TurnState.CANCELLED);
        assertThat(coordinator.watermark().bufferedMembers()).isZero(); executor.drain();
    }

    @Test
    void switchingModesRequiresIdleSchedulerAndDisablesLegacySubmission() throws Exception {
        var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            var busy = scheduler.submit("legacy", deadline(), () -> { entered.countDown(); awaitIgnoringInterrupt(release); }, () -> fail("Unexpected rejection"), () -> {});
            awaitLatch(entered);
            assertThatThrownBy(() -> scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {}))
                    .isInstanceOf(IllegalStateException.class);
            release.countDown(); busy.toCompletableFuture().get(3, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(scheduler::isIdle);
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {}, clock::get, timer);
            assertThatThrownBy(() -> scheduler.submit("legacy", deadline(), () -> {}, () -> {}, () -> {}))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {}))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new TurnCoordinator(ai("workers", "1"), aggregation(), decision("max-concurrent", "2"),
                    Duration.ofSeconds(40), executor, timer, clock::get, (ticket, snapshot, deadline) -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { release.countDown(); scheduler.stop(); }
    }

    @Test
    void completionCallbacksRunOutsideStateLockAndCanQueryFromAnotherThread() throws Exception {
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket); else coordinator.completeDecision(ticket, now());
        });
        var query = Executors.newSingleThreadExecutor();
        try {
            var first = admit("user", 1);
            var callback = first.completion().thenApply(result -> {
                try { return query.submit(coordinator::watermark).get(1, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Query interrupted"); }
                catch (ExecutionException | TimeoutException failure) { throw new IllegalStateException("Completion held state lock"); }
            });
            executor.drain();
            assertThat(callback.toCompletableFuture().get(1, TimeUnit.SECONDS).inFlightJobs()).isZero();
            assertThat(coordinator.isIdle()).isTrue();
        } finally { query.shutdownNow(); }
    }
}
