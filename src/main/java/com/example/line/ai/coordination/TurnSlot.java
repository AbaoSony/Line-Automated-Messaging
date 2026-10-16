package com.example.line.ai.coordination;

import com.example.line.ai.aggregation.InboundMember;
import com.example.line.ai.aggregation.AggregationProperties;
import com.example.line.ai.decision.DecisionProperties;
import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;
import com.example.line.ai.aggregation.TurnBudget;
import com.example.line.ai.aggregation.TurnBuffer;
import com.example.line.ai.aggregation.TurnSnapshot;
import com.example.line.common.execution.Deadline;
import com.example.line.ai.coordination.credentials.ReplyPermit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 由唯一协调器锁保护的轮次与会话槽位数据，不维护用户映射或自行执行外部 I/O。 */
final class TurnSlot {
    final UUID id = UUID.randomUUID();
    final UUID sendAttemptId = UUID.randomUUID();
    final String user;
    final long epoch;
    final List<TurnBuffer.Membership> members = new ArrayList<>();
    final LinkedHashMap<String, CompletableFuture<TurnResult>> completions = new LinkedHashMap<>();
    final LinkedHashMap<String, String> messageEvents = new LinkedHashMap<>();
    TurnBudget budget;
    TurnState state = TurnState.DECIDING;
    TurnResult result;
    long inputVersion, generationVersion, callSequence, alarmVersion;
    long readyAt;
    int characters, decisions, generations, regenerations;
    boolean frozen, inFlight, ruleWaited, fallback, readyMarked, published;
    TurnTimer.Cancellation collectionTimer, expiryTimer, alarmTimer;
    Call call;
    TurnJob job;
    TurnTicket sendTicket;
    String replyToken;

    TurnSlot(String user, long epoch, TurnBudget budget, boolean fallback) {
        this.user = user;
        this.epoch = epoch;
        this.budget = budget;
        this.fallback = fallback;
    }

    TurnAdmission add(InboundMember member, TurnBuffer.Membership membership, Deadline deadline, String token) {
        members.add(membership);
        if (token != null && !token.isBlank()) replyToken = token;
        characters += member.text().length();
        budget = budget.including(deadline);
        inputVersion++;
        var completion = new CompletableFuture<TurnResult>();
        completions.put(member.eventId(), completion);
        messageEvents.put(member.messageId(), member.eventId());
        return new TurnAdmission(true, false, id, completion.minimalCompletionStage());
    }

    TurnAdmission duplicate(InboundMember member) {
        String event = completions.containsKey(member.eventId()) ? member.eventId() : messageEvents.get(member.messageId());
        return event == null ? null : new TurnAdmission(true, true, id, completions.get(event).minimalCompletionStage());
    }

    boolean canCollect(InboundMember member, AggregationProperties aggregation) {
        return !frozen && Set.of(TurnState.DECIDING, TurnState.WAITING, TurnState.READY).contains(state)
                && budget.collectionDeadline().permits(Duration.ZERO) && fits(member, aggregation);
    }
    boolean canRegenerate(InboundMember member, AggregationProperties aggregation) {
        return state == TurnState.GENERATING && budget.collectionDeadline().permits(Duration.ZERO)
                && regenerations < aggregation.maxRegenerations() && generations < aggregation.maxGenerationAttempts()
                && budget.generationDeadline().permits(aggregation.generationReserve()) && fits(member, aggregation);
    }
    private boolean fits(InboundMember member, AggregationProperties aggregation) {
        return members.size() < aggregation.maxMembers() && member.text().length() <= aggregation.maxCharacters() - characters;
    }
    Call start(TurnTicket.Kind type, Duration generationLimit, DecisionProperties decision,
               LongSupplier clock, TurnBuffer buffer) {
        Deadline deadline;
        if (type == TurnTicket.Kind.GENERATION) {
            budget = budget.beginGeneration(generationLimit);
            frozen = true; state = TurnState.GENERATING;
            generations++; generationVersion++; deadline = budget.generationDeadline();
        } else {
            if (type == TurnTicket.Kind.DECISION) decisions++;
            deadline = new Deadline(budget.totalDeadline().startedNanos(), Math.min(budget.collectionDeadline().expiresNanos(),
                    clock.getAsLong() + decision.attemptTimeout().toNanos()), clock);
        }
        var ticket = new TurnTicket(user, epoch, id, inputVersion, generationVersion, ++callSequence, type);
        var snapshot = buffer.snapshot(id, epoch, inputVersion, generationVersion, members);
        call = new Call(ticket, snapshot, deadline);
        return call;
    }

    boolean startSending(TurnTicket ticket, boolean closing) {
        if (state != TurnState.GENERATING || ticket.kind() != TurnTicket.Kind.GENERATION
                || !budget.generationDeadline().permits(Duration.ZERO) || closing) return false;
        state = TurnState.SENDING; sendTicket = ticket;
        if (alarmTimer != null) alarmTimer.cancel();
        alarmVersion++; return true;
    }
    ReplyPermit takePermit() {
        var permit = new ReplyPermit(replyToken, budget.totalDeadline(),sendAttemptId); replyToken = null; return permit;
    }
    void cancelTimers() {
        if (collectionTimer != null) collectionTimer.cancel();
        if (expiryTimer != null) expiryTimer.cancel();
        if (alarmTimer != null) alarmTimer.cancel();
        collectionTimer = expiryTimer = alarmTimer = null;
        alarmVersion++;
    }

    static final class Session {
        final String user; final long epoch; long sequence; TurnSlot active, pending; boolean closing, queued;
        Session(String user, long epoch) { this.user = user; this.epoch = epoch; }
    }

    /** 在实际执行与清理结束前保持调用与线程关联，避免取消迟到后中断复用线程。 */
    static final class Call {
        final TurnTicket ticket;
        final TurnSnapshot snapshot;
        final Deadline deadline;
        boolean answered;
        private Thread thread;

        Call(TurnTicket ticket, TurnSnapshot snapshot, Deadline deadline) {
            this.ticket = ticket;
            this.snapshot = snapshot;
            this.deadline = deadline;
            thread = Thread.currentThread();
        }

        synchronized void cancel() { if (thread != null) thread.interrupt(); }
        synchronized void detach() { thread = null; }
    }
}
