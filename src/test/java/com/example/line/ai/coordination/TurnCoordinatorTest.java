package com.example.line.ai.coordination;

import com.example.line.ai.aggregation.InboundMember;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;

/** 用可控时钟验证轮次转换、版本、固定等待、共享调用上限和不同用户公平执行。 */
class TurnCoordinatorTest extends TurnTestSupport {
    @Test void lateAdviceCannotApplyAfterAttemptDeadlineEvenBeforeItsExecutionHasExited(){
        var rejected=new java.util.concurrent.atomic.AtomicBoolean();
        setup((ticket,snapshot,deadline) -> {
            if(ticket.kind()==TurnTicket.Kind.DECISION){timer.advanceTo(2);rejected.set(!coordinator.completeDecision(ticket,now()));}
            else if(ticket.kind()==TurnTicket.Kind.RULE)coordinator.completeDecision(ticket,now());else accepted(ticket);
        });
        var first=admit("user",1);executor.drain();assertThat(rejected).isTrue();assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
    }
    @Test void timedOutModelUsesLatestRuleSnapshotAndOnlyOneRuleWaitBeforeNormalGeneration(){
        var order=new java.util.ArrayList<TurnTicket.Kind>();
        setup((ticket,snapshot,deadline) -> {
            order.add(ticket.kind());
            if(ticket.kind()==TurnTicket.Kind.DECISION){timer.advanceTo(2);return;}
            if(ticket.kind()==TurnTicket.Kind.RULE)coordinator.completeDecision(ticket,waitMore(100));
            else accepted(ticket);
        });
        var first=admit("user",1);executor.drain();
        assertThat(order).containsExactly(TurnTicket.Kind.DECISION,TurnTicket.Kind.RULE);
        assertThat(first.completion().toCompletableFuture().isDone()).isFalse();
        timer.advanceTo(3);executor.drain();assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(order).containsExactly(TurnTicket.Kind.DECISION,TurnTicket.Kind.RULE,TurnTicket.Kind.RULE,TurnTicket.Kind.GENERATION);
    }
    @Test
    void threeMessagesBeforeFirstExecutionShareOneGenerationOneSendAndOrderedSnapshot() {
        var kinds = new ArrayList<TurnTicket.Kind>();
        var snapshots = new ArrayList<List<InboundMember>>();
        setup((ticket, snapshot, deadline) -> {
            kinds.add(ticket.kind()); snapshots.add(List.copyOf(snapshot.members()));
            assertThat(MDC.get("trace_id")).isEqualTo(snapshot.members().get(0).traceId());
            if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket);
            else assertThat(coordinator.completeDecision(ticket, now())).isTrue();
        });
        var one = admit("user", 1); clock.set(Duration.ofMillis(100).toNanos());
        var two = admit("user", 2); var three = admit("user", 3);
        assertThat(one.turnId()).isEqualTo(two.turnId()).isEqualTo(three.turnId());
        var duplicate = admit("user", 2);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(coordinator.watermark().bufferedMembers()).isEqualTo(3);
        executor.drain();
        assertThat(kinds).containsExactly(TurnTicket.Kind.DECISION, TurnTicket.Kind.GENERATION);
        assertThat(snapshots.get(1)).extracting(InboundMember::sequence).containsExactly(1L, 2L, 3L);
        assertThat(snapshots.get(1)).extracting(InboundMember::traceId).doesNotHaveDuplicates();
        for (var admission : List.of(one, two, three, duplicate)) assertThat(result(admission).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(coordinator.isIdle()).isTrue(); assertThat(timer.pending()).isZero();
        assertThat(MDC.get("user_id")).isNull();
    }

    @Test
    void ruleWaitsOnlyOnceAndAQuietUserAdvancesWithoutNewInputOrOccupiedWorkers() {
        var kinds = new ArrayList<TurnTicket.Kind>();
        setup((ticket, snapshot, deadline) -> {
            kinds.add(ticket.kind());
            if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket);
            else coordinator.completeDecision(ticket, waitMore(30));
        }, "mode", "RULE");
        var first = admit("user", 1); executor.next();
        assertThat(coordinator.state("user", first.turnId())).contains(TurnState.WAITING);
        assertThat(coordinator.watermark().inFlightJobs()).isZero();
        timer.advanceTo(1); executor.drain();
        assertThat(kinds).containsExactly(TurnTicket.Kind.RULE, TurnTicket.Kind.RULE, TurnTicket.Kind.GENERATION);
        assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(coordinator.isIdle()).isTrue();
    }

    @Test
    void continuousModelWaitStopsAtTwoPhysicalDecisionCalls() {
        var kinds = new ArrayList<TurnTicket.Kind>();
        setup((ticket, snapshot, deadline) -> {
            kinds.add(ticket.kind());
            if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket);
            else coordinator.completeDecision(ticket, waitMore(1));
        });
        var first = admit("user", 1); executor.next(); timer.advanceTo(1); executor.drain();
        assertThat(kinds).containsExactly(TurnTicket.Kind.DECISION, TurnTicket.Kind.DECISION, TurnTicket.Kind.GENERATION);
        assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
    }

    @Test
    void fixedCollectionDeadlineOverridesVeryLongWaitAndLateCancelledTimersCannotReviveTurn() {
        var kinds = new ArrayList<TurnTicket.Kind>();
        setup((ticket, snapshot, deadline) -> {
            kinds.add(ticket.kind());
            if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket);
            else coordinator.completeDecision(ticket, waitMore(100));
        });
        var first = admit("user", 1); executor.next();
        timer.advanceTo(3);
        assertThat(coordinator.state("user", first.turnId())).contains(TurnState.WAITING);
        assertThat(coordinator.watermark().inFlightJobs()).isZero();
        timer.advanceTo(6); executor.drain();
        assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
        timer.cancelledActions().forEach(Runnable::run); executor.drain();
        assertThat(kinds).containsExactly(TurnTicket.Kind.DECISION, TurnTicket.Kind.GENERATION);
        assertThat(timer.pending()).isZero(); assertThat(coordinator.isIdle()).isTrue();
    }

    @Test
    void newInputDuringGenerationInvalidatesOldOutputAndSharesTheOriginalGenerationDeadline() {
        var generations = new ArrayList<List<Long>>();
        var deadlines = new ArrayList<Long>();
        var old = new AtomicReference<TurnTicket>(); var second = new AtomicReference<TurnAdmission>();
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
            generations.add(snapshot.members().stream().map(InboundMember::sequence).toList()); deadlines.add(deadline.expiresNanos());
            if (old.get() == null) {
                old.set(ticket); clock.set(Duration.ofSeconds(1).toNanos()); second.set(admit("user", 2));
                assertThat(coordinator.beginSending(ticket)).isFalse();
            } else { assertThat(coordinator.beginSending(old.get())).isFalse(); accepted(ticket); }
        });
        var first = admit("user", 1); executor.drain();
        assertThat(generations).containsExactly(List.of(1L), List.of(1L, 2L));
        assertThat(deadlines).containsExactly(Duration.ofSeconds(40).toNanos(), Duration.ofSeconds(40).toNanos());
        assertThat(first.turnId()).isEqualTo(second.get().turnId());
        assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(coordinator.reserveGenerationRetry(old.get())).isFalse();
    }

    @Test
    void pendingCollectionRunsFromItsOwnFirstReceiptAndFrozenPendingRejectsThirdTurn() {
        var pending = new AtomicReference<TurnAdmission>(); var third = new AtomicReference<TurnAdmission>();
        var generated = new ArrayList<List<Long>>();
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
            generated.add(snapshot.members().stream().map(InboundMember::sequence).toList());
            assertThat(coordinator.beginSending(ticket)).isTrue();
            if (snapshot.members().get(0).sequence() == 1) {
                clock.set(Duration.ofSeconds(1).toNanos()); pending.set(admit("user", 2));
                timer.advanceTo(7); third.set(admit("user", 3));
                assertThat(coordinator.state("user", pending.get().turnId())).contains(TurnState.READY);
            }
            coordinator.finishSending(ticket, TurnState.ACCEPTED);
        });
        var first = admit("user", 1); executor.drain();
        assertThat(generated).containsExactly(List.of(1L), List.of(2L));
        assertThat(first.turnId()).isNotEqualTo(pending.get().turnId());
        assertThat(result(pending.get()).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(third.get().admitted()).isFalse();
        assertThat(result(third.get()).reason()).isEqualTo(TurnResult.Reason.CAPACITY);
    }

    @Test
    void aReservedRetryPreventsSameTurnRegenerationAndCannotReserveAThirdRequest() {
        var following = new AtomicReference<TurnAdmission>(); var generated = new ArrayList<List<Long>>();
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
            generated.add(snapshot.members().stream().map(InboundMember::sequence).toList());
            if (snapshot.members().get(0).sequence() == 1) {
                assertThat(coordinator.reserveGenerationRetry(ticket)).isTrue();
                assertThat(coordinator.reserveGenerationRetry(ticket)).isFalse();
                clock.set(Duration.ofSeconds(1).toNanos()); following.set(admit("user", 2));
            }
            accepted(ticket);
        });
        var first = admit("user", 1); executor.drain();
        assertThat(first.turnId()).isNotEqualTo(following.get().turnId());
        assertThat(generated).containsExactly(List.of(1L), List.of(2L));
        assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(result(following.get()).state()).isEqualTo(TurnState.ACCEPTED);
    }

    @Test
    void generationFailureAndUnfinishedSendReturnExplicitTerminalResultsWithoutExceptionalCompletion() {
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() != TurnTicket.Kind.GENERATION) coordinator.completeDecision(ticket, now());
            else throw new IllegalStateException("fictional failure");
        });
        var failed = admit("user", 1); executor.drain();
        assertThat(result(failed)).isEqualTo(new TurnResult(TurnState.FAILED, TurnResult.Reason.WORK_FAILURE));
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() != TurnTicket.Kind.GENERATION) coordinator.completeDecision(ticket, now());
            else assertThat(coordinator.beginSending(ticket)).isTrue();
        });
        var unknown = admit("user", 2); executor.drain();
        assertThat(result(unknown).state()).isEqualTo(TurnState.UNKNOWN);
        assertThat(coordinator.isIdle()).isTrue();
    }

    @Test
    void bodyIsPrioritizedOverQueuedDecisionsWhileDecisionSubLimitAndWorkerLimitRemainBounded() {
        var order = new ArrayList<String>();
        coordinator = new TurnCoordinator(ai("workers", "2"), aggregation(), decision(), Duration.ofSeconds(40), executor, timer, clock::get,
                (ticket, snapshot, deadline) -> {
                    order.add(ticket.userId() + ":" + ticket.kind());
                    assertThat(coordinator.watermark().inFlightDecisions()).isLessThanOrEqualTo(1);
                    assertThat(coordinator.watermark().inFlightJobs()).isLessThanOrEqualTo(2);
                    if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket); else coordinator.completeDecision(ticket, now());
                });
        var a = admit("A", 1); var b = admit("B", 2); var c = admit("C", 3);
        assertThat(executor.pending()).isEqualTo(1);
        executor.drain();
        assertThat(order).startsWith("A:DECISION", "A:GENERATION", "B:DECISION");
        for (var admission : List.of(a, b, c)) assertThat(result(admission).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(coordinator.isIdle()).isTrue();
    }

    @Test
    void fullExecutionQueueRejectsWithoutStealingExistingCapacityAndTransitionDoesNotRejectItsOwnBody() {
        coordinator = new TurnCoordinator(ai("workers", "1", "queue-capacity", "1"), aggregation(), decision(),
                Duration.ofSeconds(40), executor, timer, clock::get, (ticket, snapshot, deadline) -> {
                    if (ticket.kind() == TurnTicket.Kind.GENERATION) accepted(ticket); else coordinator.completeDecision(ticket, now());
                });
        var a = admit("A", 1); var b = admit("B", 2); var c = admit("C", 3);
        assertThat(result(c).state()).isEqualTo(TurnState.REJECTED);
        assertThat(coordinator.watermark().queuedJobs()).isEqualTo(1);
        executor.drain();
        assertThat(result(a).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(result(b).state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(coordinator.isIdle()).isTrue();
    }

    @Test
    void outOfOrderAndOversizedMembersCannotChangeExistingInput() {
        var inputs = new ArrayList<Long>();
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() == TurnTicket.Kind.GENERATION) { inputs.addAll(snapshot.members().stream().map(InboundMember::sequence).toList()); accepted(ticket); }
            else coordinator.completeDecision(ticket, now());
        });
        var first = admit("user", 2); var older = admit("user", 1);
        var original = member("user", 3);
        var tooLong = coordinator.admit(new InboundMember(original.userId(), original.eventId(), original.messageId(), "長".repeat(4097),
                original.occurredAt(), original.receivedAt(), original.sequence(), original.traceId()), deadline());
        assertThat(result(older).reason()).isEqualTo(TurnResult.Reason.OUT_OF_ORDER);
        assertThat(result(tooLong).reason()).isEqualTo(TurnResult.Reason.INPUT_TOO_LONG);
        executor.drain(); assertThat(inputs).containsExactly(2L); assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);
    }

    @Test
    void aPromotedPendingTurnJoinsBehindAnotherReadyUserRatherThanReusingOldQueuePosition() {
        var generated = new ArrayList<String>(); var pending = new AtomicReference<TurnAdmission>();
        coordinator = new TurnCoordinator(ai("workers", "1"), aggregation(), decision(), Duration.ofSeconds(40), executor, timer, clock::get,
                (ticket, snapshot, deadline) -> {
                    if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
                    generated.add(ticket.userId()); assertThat(coordinator.beginSending(ticket)).isTrue();
                    if (ticket.userId().equals("A") && pending.get() == null) { pending.set(admit("A", 3)); timer.advanceTo(6); }
                    coordinator.finishSending(ticket, TurnState.ACCEPTED);
                });
        var first = admit("A", 1); executor.next(); var other = admit("B", 2); executor.drain();
        assertThat(generated).containsExactly("A", "B", "A");
        for (var admission : List.of(first, other, pending.get())) assertThat(result(admission).state()).isEqualTo(TurnState.ACCEPTED);
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"ACCEPTED", "FAILED", "UNKNOWN"})
    void explicitSendResultEndsOnceAndCannotBeReplacedByAnotherOutcome(TurnState outcome) {
        setup((ticket, snapshot, deadline) -> {
            if (ticket.kind() != TurnTicket.Kind.GENERATION) { coordinator.completeDecision(ticket, now()); return; }
            assertThat(coordinator.beginSending(ticket)).isTrue();
            assertThat(coordinator.finishSending(ticket, outcome)).isTrue();
            assertThat(coordinator.finishSending(ticket, TurnState.ACCEPTED)).isFalse();
            assertThat(coordinator.beginSending(ticket)).isFalse();
        });
        var first = admit("user", 1); executor.drain();
        assertThat(result(first).state()).isEqualTo(outcome); assertThat(coordinator.isIdle()).isTrue();
    }
}
