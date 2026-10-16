package com.example.line.ai.coordination;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 用真实有界执行线程与同步屏障验证迟到、取消、发送竞态及停机，没有真实外部调用。 */
class TurnLifecycleTest extends TurnTestSupport {
    @Test
    void inputChangesDuringDecisionDiscardOldAdviceAndEvaluateOnlyLatestSnapshot() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var decisions = new CopyOnWriteArrayList<List<Long>>(); var discarded = new AtomicBoolean();
        var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry());
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() == TurnTicket.Kind.GENERATION) { accepted(ticket); return; }
                decisions.add(snapshot.members().stream().map(member -> member.sequence()).toList());
                if (decisions.size() == 1) {
                    entered.countDown(); awaitIgnoringInterrupt(release);
                    discarded.set(!coordinator.completeDecision(ticket, waitMore(100)));
                } else coordinator.completeDecision(ticket, now());
            }, clock::get, timer);
            var first = admit("user", 1); awaitLatch(entered);
            clock.set(Duration.ofSeconds(1).toNanos()); var two = admit("user", 2); var three = admit("user", 3);
            assertThat(coordinator.watermark().inFlightDecisions()).isEqualTo(1);
            assertThat(first.completion().toCompletableFuture().isDone()).isFalse();
            release.countDown();
            assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(result(two).state()).isEqualTo(TurnState.ACCEPTED); assertThat(result(three).state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(discarded).isTrue(); assertThat(decisions).containsExactly(List.of(1L), List.of(1L, 2L, 3L));
        } finally { release.countDown(); scheduler.stop(); }
    }

    @Test
    void deleteCancellationRetainsActualWorkAndSnapshotUntilExitThenNewSessionRejectsOldTicket() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var old = new AtomicReference<TurnTicket>(); var fresh = new AtomicReference<TurnTicket>(); var oldRejected = new AtomicBoolean();
        var scheduler = new SessionScheduler(ai("max-users", "1"), new SimpleMeterRegistry());
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
                if (snapshot.members().get(0).sequence() == 1) {
                    old.set(ticket); entered.countDown(); awaitIgnoringInterrupt(release);
                    assertThat(snapshot.members()).hasSize(1); oldRejected.set(!coordinator.beginSending(ticket));
                } else { fresh.set(ticket); assertThat(coordinator.beginSending(old.get())).isFalse(); accepted(ticket); }
            }, clock::get, timer);
            var first = admit("user", 1); awaitLatch(entered);
            new AiDeletionGuard(scheduler).cancelPending("user");
            assertThat(coordinator.state("user", first.turnId())).contains(TurnState.CANCELLED);
            assertThat(first.completion().toCompletableFuture().isDone()).isFalse();
            assertThat(coordinator.watermark().inFlightJobs()).isEqualTo(1);
            assertThat(coordinator.watermark().bufferedMembers()).isEqualTo(1);
            assertThatThrownBy(() -> scheduler.purge("user")).isInstanceOf(IllegalStateException.class);
            assertThat(result(admit("other-user", 2)).reason()).isEqualTo(TurnResult.Reason.CAPACITY);
            assertThat(result(admit("user", 3)).reason()).isEqualTo(TurnResult.Reason.CLOSED);
            release.countDown();
            assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.CANCELLED);
            await().atMost(Duration.ofSeconds(3)).until(coordinator::isIdle);
            scheduler.purge("user"); scheduler.purge("user");
            var next = admit("user", 4);
            assertThat(next.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(oldRejected).isTrue(); assertThat(fresh.get().epoch()).isGreaterThan(old.get().epoch());
        } finally { release.countDown(); scheduler.stop(); }
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"ACCEPTED", "UNKNOWN"})
    void sendingIsNotFalselyCancelledAndCompletesWithActualOrUnknownResult(TurnState expected) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry());
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
                assertThat(coordinator.beginSending(ticket)).isTrue(); entered.countDown(); awaitIgnoringInterrupt(release);
                if (expected == TurnState.ACCEPTED) assertThat(coordinator.finishSending(ticket, TurnState.ACCEPTED)).isTrue();
            }, clock::get, timer);
            var first = admit("user", 1); awaitLatch(entered); scheduler.cancel("user"); timer.advanceTo(45);
            assertThat(coordinator.state("user", first.turnId())).contains(TurnState.SENDING);
            assertThat(first.completion().toCompletableFuture().isDone()).isFalse(); assertThat(coordinator.isIdle()).isFalse();
            release.countDown();
            assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(expected);
            await().atMost(Duration.ofSeconds(3)).until(coordinator::isIdle);
        } finally { release.countDown(); scheduler.stop(); }
    }

    @Test
    void twoConcurrentClaimsHaveExactlyOneSendWinner() throws Exception {
        var winners = new AtomicInteger(); var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry());
        var claimers = Executors.newFixedThreadPool(2);
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
                var start = new CountDownLatch(1);
                Callable<Boolean> claim = () -> { awaitLatch(start); return coordinator.beginSending(ticket); };
                var a = claimers.submit(claim); var b = claimers.submit(claim); start.countDown();
                try {
                    if (a.get(3, TimeUnit.SECONDS)) winners.incrementAndGet();
                    if (b.get(3, TimeUnit.SECONDS)) winners.incrementAndGet();
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Claim interrupted"); }
                catch (ExecutionException | TimeoutException failure) { throw new IllegalStateException("Claim failed"); }
                assertThat(coordinator.finishSending(ticket, TurnState.ACCEPTED)).isTrue();
                assertThat(coordinator.beginSending(ticket)).isFalse();
                assertThat(coordinator.finishSending(ticket, TurnState.UNKNOWN)).isFalse();
            }, clock::get, timer);
            var first = admit("user", 1);
            assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(winners).hasValue(1);
        } finally { claimers.shutdownNow(); scheduler.stop(); }
    }

    @Test
    void schedulerStopClosesAdmissionAndWaitsForActualExecutionCleanup() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry()); var stopper = Executors.newSingleThreadExecutor();
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() != TurnTicket.Kind.GENERATION) coordinator.completeDecision(ticket, now());
                else { entered.countDown(); awaitIgnoringInterrupt(release); assertThat(coordinator.beginSending(ticket)).isFalse(); }
            }, clock::get, timer);
            var first = admit("user", 1); awaitLatch(entered); var stopping = stopper.submit(() -> { scheduler.stop(); });
            await().atMost(Duration.ofSeconds(3)).until(() -> coordinator.state("user", first.turnId()).orElse(null) == TurnState.CANCELLED);
            assertThat(result(admit("new-user", 2)).reason()).isEqualTo(TurnResult.Reason.CLOSED);
            assertThat(stopping.isDone()).isFalse(); release.countDown(); stopping.get(3, TimeUnit.SECONDS);
            assertThat(result(first).state()).isEqualTo(TurnState.CANCELLED); assertThat(scheduler.isIdle()).isTrue();
        } finally { release.countDown(); scheduler.stop(); stopper.shutdownNow(); }
    }

    @Test
    void aReadyUserExpiresWhileAnotherExecutesWithoutTakingAWorkerOrRestartingItsWait() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var invoked = new CopyOnWriteArrayList<String>(); var scheduler = new SessionScheduler(ai("workers", "1"), new SimpleMeterRegistry());
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                invoked.add(ticket.userId());
                if (ticket.kind() != TurnTicket.Kind.GENERATION) coordinator.completeDecision(ticket, now());
                else { entered.countDown(); awaitIgnoringInterrupt(release); accepted(ticket); }
            }, clock::get, timer);
            var first = admit("A", 1); awaitLatch(entered); var queued = admit("B", 2);
            timer.advanceTo(6); assertThat(coordinator.state("B", queued.turnId())).contains(TurnState.READY);
            timer.advanceTo(9);
            assertThat(result(queued)).isEqualTo(new TurnResult(TurnState.EXPIRED, TurnResult.Reason.QUEUE_TIMEOUT));
            assertThat(invoked).containsExactly("A", "A"); assertThat(coordinator.watermark().inFlightJobs()).isEqualTo(1);
            release.countDown(); assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
        } finally { release.countDown(); scheduler.stop(); }
    }

    @Test
    void regenerationWaitsForActualOldInvocationExitAndDropsItsLateOutput() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var oldRejected = new AtomicBoolean(); var snapshots = new CopyOnWriteArrayList<List<Long>>();
        var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry());
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
                snapshots.add(snapshot.members().stream().map(member -> member.sequence()).toList());
                if (calls.incrementAndGet() == 1) {
                    entered.countDown(); awaitIgnoringInterrupt(release); oldRejected.set(!coordinator.beginSending(ticket));
                } else accepted(ticket);
            }, clock::get, timer);
            var first = admit("user", 1); awaitLatch(entered); clock.set(Duration.ofSeconds(1).toNanos());
            var second = admit("user", 2);
            assertThat(calls).hasValue(1); assertThat(coordinator.watermark().inFlightJobs()).isEqualTo(1);
            assertThat(coordinator.watermark().bufferedMembers()).isEqualTo(2);
            release.countDown();
            assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(result(second).state()).isEqualTo(TurnState.ACCEPTED); assertThat(calls).hasValue(2); assertThat(oldRejected).isTrue();
            assertThat(snapshots).containsExactly(List.of(1L), List.of(1L, 2L));
        } finally { release.countDown(); scheduler.stop(); }
    }

    @Test
    void alreadyAppliedDecisionCannotApplyAgainAfterWakeWhileItsInvocationStillCleansUp() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstTicket = new AtomicReference<TurnTicket>(); var decisions = new AtomicInteger();
        var scheduler = new SessionScheduler(ai(), new SimpleMeterRegistry());
        try {
            coordinator = scheduler.enableTurns(aggregation(), decision(), Duration.ofSeconds(40), (ticket, snapshot, deadline) -> {
                if (ticket.kind() == TurnTicket.Kind.GENERATION) { accepted(ticket); return; }
                if (decisions.incrementAndGet() == 1) {
                    firstTicket.set(ticket); assertThat(coordinator.completeDecision(ticket, waitMore(1))).isTrue();
                    entered.countDown(); awaitIgnoringInterrupt(release);
                } else coordinator.completeDecision(ticket, now());
            }, clock::get, timer);
            var first = admit("user", 1); awaitLatch(entered); timer.advanceTo(1);
            assertThat(coordinator.state("user", first.turnId())).contains(TurnState.DECIDING);
            assertThat(coordinator.completeDecision(firstTicket.get(), now())).isFalse();
            release.countDown();
            assertThat(first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(decisions).hasValue(2);
        } finally { release.countDown(); scheduler.stop(); }
    }
}
