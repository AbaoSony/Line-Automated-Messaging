package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.ai.aggregation.AggregationProperties;
import com.example.line.ai.aggregation.InboundMember;
import com.example.line.ai.decision.DecisionProperties;
import com.example.line.ai.decision.TimingDecision;
import com.example.line.common.execution.Deadline;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 测试专用时钟、可控定时及执行器；所有业务标识与原文均虚构。 */
class TurnTestSupport {
    final AtomicLong clock = new AtomicLong();
    final ManualTimer timer = new ManualTimer();
    final ManualExecutor executor = new ManualExecutor();
    TurnCoordinator coordinator;

    AiProperties ai(String... pairs) { return bind("line.ai", AiProperties.class, pairs); }
    AggregationProperties aggregation(String... pairs) { return bind("line.ai.aggregation", AggregationProperties.class, pairs); }
    DecisionProperties decision(String... pairs) { return bind("line.ai.decision", DecisionProperties.class, pairs); }
    private <T> T bind(String prefix, Class<T> type, String... pairs) {
        var env = new MockEnvironment();
        for (int i = 0; i < pairs.length; i += 2) env.withProperty(prefix + "." + pairs[i], pairs[i + 1]);
        return Binder.get(env).bindOrCreate(prefix, type);
    }
    void setup(TurnWork work, String... decisionPairs) {
        coordinator = new TurnCoordinator(ai(), aggregation(), decision(decisionPairs), Duration.ofSeconds(40), executor, timer, clock::get, work);
    }
    InboundMember member(String user, long sequence) {
        var at = Instant.parse("2026-10-16T00:00:00Z").plusNanos(clock.get());
        return new InboundMember(user, "fictional-event-" + sequence, "fictional-message-" + sequence,
                "完整原文" + sequence, at, at, sequence, String.format("00000000-0000-4000-8000-%012d", sequence));
    }
    Deadline deadline() { return Deadline.from(clock.get(), Duration.ofSeconds(45), clock::get); }
    TurnAdmission admit(String user, long sequence) { return coordinator.admit(member(user, sequence), deadline()); }
    TurnResult result(TurnAdmission admission) { return admission.completion().toCompletableFuture().join(); }
    TimingDecision now() { return new TimingDecision(TimingDecision.Action.REPLY_NOW, Duration.ZERO, TimingDecision.Reason.COMPLETE_INPUT); }
    TimingDecision waitMore(long seconds) { return new TimingDecision(TimingDecision.Action.WAIT_FOR_MORE, Duration.ofSeconds(seconds), TimingDecision.Reason.POSSIBLE_CONTINUATION); }
    void accepted(TurnTicket ticket) {
        assertThat(coordinator.beginSending(ticket)).isTrue();
        assertThat(coordinator.finishSending(ticket, TurnState.ACCEPTED)).isTrue();
    }
    static void awaitLatch(CountDownLatch latch) {
        try { assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (latch.getCount() != 0) {
            try { if (latch.await(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS)) break; throw new AssertionError("Test latch timed out"); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    final class ManualTimer implements TurnTimer {
        private final List<Entry> pending = new ArrayList<>();
        private final List<Entry> history = new ArrayList<>();
        @Override public synchronized Cancellation schedule(long at, Runnable action) {
            var entry = new Entry(at, action); pending.add(entry); history.add(entry);
            return () -> { synchronized (ManualTimer.this) { entry.cancelled = true; pending.remove(entry); } };
        }
        void advanceTo(long seconds) {
            clock.set(Duration.ofSeconds(seconds).toNanos());
            int iterations = 0;
            while (true) {
                Entry entry;
                synchronized (this) {
                    entry = pending.stream().filter(value -> value.at <= clock.get()).min(Comparator.comparingLong(value -> value.at)).orElse(null);
                    if (entry == null) return;
                    pending.remove(entry);
                }
                if (++iterations > 1000) throw new AssertionError("Unbounded control timer loop");
                entry.action.run();
            }
        }
        synchronized int pending() { return pending.size(); }
        synchronized List<Runnable> cancelledActions() { return history.stream().filter(entry -> entry.cancelled).map(entry -> entry.action).toList(); }
        private final class Entry {
            final long at; final Runnable action; boolean cancelled;
            Entry(long at, Runnable action) { this.at = at; this.action = action; }
        }
    }
    static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable action) { queued.addLast(action); }
        void next() {
            Runnable action;
            synchronized (this) { action = queued.removeFirst(); }
            action.run();
        }
        synchronized int pending() { return queued.size(); }
        void drain() {
            int iterations = 0;
            while (pending() != 0) {
                if (++iterations > 1000) throw new AssertionError("Unbounded model execution loop");
                next();
            }
        }
    }
}
