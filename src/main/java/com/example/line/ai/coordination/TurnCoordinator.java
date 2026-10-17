package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.ai.aggregation.*;
import com.example.line.ai.decision.DecisionProperties;
import com.example.line.ai.decision.TimingDecision;
import com.example.line.ai.coordination.TurnSlot.Session;
import com.example.line.ai.coordination.credentials.ReplyPermit;
import com.example.line.common.execution.Deadline;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * P2 唯一会话所有者，由 SessionScheduler 在空闲时互斥切换启用，复用其工作及定时线程。
 * 所有状态在同一锁下变更；执行、完成通知与中断在锁外，取消未实际退出的调用仍占资源。
 */
public final class TurnCoordinator {
    private final Object gate = new Object();
    private final AiProperties ai;
    private final AggregationProperties aggregation;
    private final DecisionProperties decision;
    private final Duration generationLimit;
    private final Executor executor;
    private final TurnClock timers;
    private final LongSupplier clock;
    private final TurnWork work;
    private final TurnBuffer buffer;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ArrayDeque<Session> ready = new ArrayDeque<>();
    private final List<Runnable> notifications = new ArrayList<>();
    private long epoch;
    private int running, deciding;
    private boolean accepting = true;
    TurnCoordinator(AiProperties ai, AggregationProperties aggregation, DecisionProperties decision,
                    Duration generationLimit, Executor executor, TurnTimer timer, LongSupplier clock, TurnWork work) {
        this.ai = Objects.requireNonNull(ai); this.aggregation = Objects.requireNonNull(aggregation);
        this.decision = Objects.requireNonNull(decision); this.generationLimit = Objects.requireNonNull(generationLimit);
        this.executor = Objects.requireNonNull(executor); Objects.requireNonNull(timer);
        this.clock = Objects.requireNonNull(clock); this.work = Objects.requireNonNull(work);
        if (decision.maxConcurrent() > ai.workers() || generationLimit.isZero() || generationLimit.isNegative()
                || generationLimit.compareTo(Duration.ofSeconds(50)) > 0) throw new IllegalArgumentException("Invalid shared turn resources");
        buffer = new TurnBuffer(aggregation);
        timers = new TurnClock(timer, action -> control(() -> { action.run(); return null; }), this::live,
                turn -> { finish(turn, TurnState.REJECTED, TurnResult.Reason.CLOSED); interrupt(turn); });
    }

    /**
     * 按有序入口准入一条业务成员，重复复用原完成阶段；拒绝不更新已有轮次。
     * @param member 无凭证的原始文本业务数据
     * @param deadline 该消息 HTTP 原始入口截止
     * @return 准入/重复结果及明确业务终态
     * @throws NullPointerException 参数为空时抛出
     * @throws IllegalArgumentException 原始截止不满足配置边界时抛出
     */
    public TurnAdmission admit(InboundMember member, Deadline deadline) {
        return admit(member, deadline, null);
    }
    /**
     * 凭证与准入在同一状态锁内更新，重复、拒绝和其他轮次均不能替换当前凭证。
     * @param member 无凭证业务成员
     * @param deadline 原始入口截止
     * @param replyToken 当前即时流程凭证，不记录或持久化
     * @return 准入及业务终态
     */
    public TurnAdmission admit(InboundMember member, Deadline deadline, String replyToken) {
        Objects.requireNonNull(member); Objects.requireNonNull(deadline);
        return control(() -> admitLocked(member, new Deadline(deadline.startedNanos(), deadline.expiresNanos(), clock), replyToken));
    }

    /**
     * 应用绑定版本的时机建议；过期输出不能改变新输入或延期固定收集窗口。
     * @param ticket 执行时取得的程序版本
     * @param advice 有效 NOW/WAIT 业务建议
     * @return 当前版本仍有效且已应用时返回 true
     * @throws NullPointerException 建议为空时抛出
     */
    public boolean completeDecision(TurnTicket ticket, TimingDecision advice) {
        Objects.requireNonNull(advice);
        return control(() -> {
            TurnSlot turn = current(ticket);
            if (turn == null || turn.state != TurnState.DECIDING || turn.call.answered || ticket.kind() == TurnTicket.Kind.GENERATION
                    || ticket.kind()==TurnTicket.Kind.DECISION && !turn.call.deadline.permits(Duration.ZERO)) return false;
            turn.call.answered = true;
            long remaining = turn.budget.collectionDeadline().remaining().toNanos();
            boolean rule = ticket.kind() == TurnTicket.Kind.RULE;
            if (advice.action() == TimingDecision.Action.REPLY_NOW || remaining == 0
                    || rule && turn.ruleWaited || !rule && turn.decisions >= decision.maxAttempts()) makeReady(turn);
            else {
                long wait = Math.min(remaining, advice.waitFor().toNanos());
                if (rule) { wait = Math.min(wait, aggregation.ruleWait().toNanos()); turn.ruleWaited = true; }
                turn.state = TurnState.WAITING;
                alarm(turn, clock.getAsLong() + wait, () -> {
                    if (turn.budget.collectionDeadline().permits(Duration.ZERO)) turn.state = TurnState.DECIDING;
                    else makeReady(turn);
                });
            }
            return true;
        });
    }

    /**
     * 原子核对当前正文版本与截止并进入 SENDING，返回后执行方才能调用 LINE。
     * @param ticket 正文生成绑定版本
     * @return 只有一个合法版本能取得发送权
     */
    public boolean beginSending(TurnTicket ticket) {
        return control(() -> startSending(ticket) != null);
    }
    /**
     * 原子取得当前版本的唯一发送权并转移隔离凭证，迟到版本返回空。
     * @param ticket 当前正文版本
     * @return 有效凭证与原始总截止，不能再次取得
     */
    public ReplyPermit claimSending(TurnTicket ticket) {
        return control(() -> {
            TurnSlot turn = current(ticket);
            if (turn == null || turn.replyToken == null || turn.replyToken.isBlank()) return null;
            turn = startSending(ticket);
            if (turn == null) return null;
            return turn.takePermit();
        });
    }
    /**
     * 读取当前版本固定总截止，供锁 TTL 使用，失效版本不能继续依赖操作。
     * @param ticket 当前执行版本
     * @return 总截止，失效时为空
     */
    public Deadline totalDeadline(TurnTicket ticket) {
        synchronized (gate) { var turn = current(ticket); return turn == null ? null : turn.budget.totalDeadline(); }
    }
    private TurnSlot startSending(TurnTicket ticket) {
        TurnSlot turn = current(ticket);
        return turn != null && turn.startSending(ticket, sessions.get(turn.user).closing) ? turn : null;
    }

    /**
     * 在第二个真实正文请求之前预占共享额度；网络重试与重生成不能各获两次机会。
     * @param ticket 当前正文执行版本
     * @return 当前仍可请求且成功占用剩余额度时为 true
     */
    public boolean reserveGenerationRetry(TurnTicket ticket) {
        return control(() -> {
            TurnSlot turn = current(ticket);
            if (turn == null || ticket.kind() != TurnTicket.Kind.GENERATION || turn.state != TurnState.GENERATING
                    || turn.generations >= aggregation.maxGenerationAttempts() || !turn.budget.generationDeadline().permits(Duration.ZERO)) return false;
            turn.generations++; return true;
        });
    }

    /**
     * 明确终结未发送生成，静默不等于未完成作业，也不允许使发送中版本回退。
     * @param ticket 当前正文版本
     * @param reason 固定失败或静默原因
     * @return 当前未发送版本被终结时返回 true
     * @throws NullPointerException 当前版本有效但原因缺失时抛出
     */
    public boolean finishUnsent(TurnTicket ticket, TurnResult.Reason reason) {
        return control(() -> {
            TurnSlot turn = current(ticket);
            if (turn == null || turn.state != TurnState.GENERATING) return false;
            finish(turn, TurnState.FAILED, Objects.requireNonNull(reason)); return true;
        });
    }
    /**
     * 按实际发送结果终结；取消或截止之后，已经发送中的调用仍可报告其真实结果。
     * @param ticket 已取得发送权的版本
     * @param outcome ACCEPTED / FAILED / UNKNOWN
     * @return 当前发送仍在收尾且结果被应用时返回 true
     * @throws IllegalArgumentException 传入非发送结果状态时抛出
     */
    public boolean finishSending(TurnTicket ticket, TurnState outcome) {
        if (outcome == null || !Set.of(TurnState.ACCEPTED, TurnState.FAILED, TurnState.UNKNOWN).contains(outcome))
            throw new IllegalArgumentException("Invalid send outcome");
        return control(() -> {
            TurnSlot turn = find(ticket);
            if (turn == null || turn.state != TurnState.SENDING || !ticket.equals(turn.sendTicket)) return false;
            finish(turn, outcome, outcome == TurnState.ACCEPTED ? TurnResult.Reason.NORMAL
                    : outcome == TurnState.UNKNOWN ? TurnResult.Reason.SEND_UNKNOWN : TurnResult.Reason.SEND_FAILURE);
            return true;
        });
    }

    /**
     * 取消用户未发送轮次并失效旧回调；发送中仅请求中断，等待实际退出及结果收尾。
     * @param userId 完整用户标识
     */
    public void cancel(String userId) { control(() -> { cancelSession(sessions.get(userId),true); return null; }); }

    void requireDrained(String userId) {
        synchronized (gate) { if (sessions.containsKey(userId)) throw new IllegalStateException("User turn work has not drained"); }
    }

    /** 停止准入、取消未发送工作；线程池由 SessionScheduler 等待和关闭。 */
    public void stop() { control(() -> { accepting = false; List.copyOf(sessions.values()).forEach(session -> cancelSession(session,false)); return null; }); }

    /** @return 会话、排队、实际执行及业务快照全部排空时返回 true */
    public boolean isIdle() { synchronized (gate) { return sessions.isEmpty() && running == 0 && buffer.members() == 0; } }

    /**
     * 查询尚在内存中的轮次状态，已经排空的终态通过准入完成阶段读取。
     * @param userId 完整用户标识
     * @param turnId 稳定轮次 ID
     * @return 当前轮次状态，已经清理则为空
     */
    public Optional<TurnState> state(String userId, UUID turnId) {
        synchronized (gate) { return Optional.ofNullable(find(userId, turnId)).map(turn -> turn.state); }
    }

    /** @return 不含用户标签的当前资源水位，供后续指标装配与测试使用 */
    public Watermark watermark() {
        synchronized (gate) { return new Watermark(sessions.size(), ready.size(), running, deciding, buffer.members(), buffer.characters()); }
    }
    /** 水位区分尚未退出的执行与仍保留的共享业务原文。 */
    public record Watermark(int sessions, int queuedJobs, int inFlightJobs, int inFlightDecisions,
                            int bufferedMembers, long bufferedCharacters) {}

    private TurnAdmission admitLocked(InboundMember member, Deadline deadline, String replyToken) {
        if (!accepting) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.CLOSED);
        Session session = sessions.get(member.userId());
        if (session != null) {
            for (var turn : turns(session)) { var duplicate = turn.duplicate(member); if (duplicate != null) return duplicate; }
            if (session.closing) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.CLOSED);
            if (member.sequence() <= session.sequence) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.OUT_OF_ORDER);
        }
        if (!deadline.permits(ai.sendReserve())) return TurnAdmission.rejected(TurnState.EXPIRED, TurnResult.Reason.DEADLINE);
        if (member.text().length() > aggregation.maxCharacters()) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.INPUT_TOO_LONG);
        if (session == null && sessions.size() >= ai.maxUsers()) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.CAPACITY);
        TurnSlot target = session == null ? null : session.active;
        boolean supersede = target != null && target.canRegenerate(member, aggregation);
        if (target != null && !target.canCollect(member, aggregation) && !supersede) target = session.pending;
        if (target != null && target != (session == null ? null : session.active) && !target.canCollect(member, aggregation))
            return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.CAPACITY);
        // 一个待处理轮次冻结之后，不能绕过容量再开第三轮。
        if (target == null && session != null && session.pending != null) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.CAPACITY);
        var budget = target == null ? TurnBudget.create(deadline, aggregation, ai.sendReserve()) : null;
        var held = buffer.retain(member);
        if (held == null) return TurnAdmission.rejected(TurnState.REJECTED, TurnResult.Reason.CAPACITY);
        if (session == null) { session = new Session(member.userId(), ++epoch); sessions.put(member.userId(), session); }
        if (target == null) {
            target = new TurnSlot(member.userId(), session.epoch, budget, decision.mode() == DecisionProperties.Mode.RULE);
            if (session.active == null) session.active = target; else session.pending = target;
        }
        TurnAdmission admission = target.add(member, held, deadline, replyToken); session.sequence = member.sequence();
        if (supersede) { target.regenerations++; target.generationVersion++; target.frozen = false; interrupt(target); makeReady(target); }
        else if (target.state == TurnState.WAITING) { target.state = TurnState.DECIDING; cancelAlarm(target); }
        scheduleBounds(target);
        if (target.members.size() == aggregation.maxMembers() || target.characters == aggregation.maxCharacters()
                || !target.budget.collectionDeadline().permits(Duration.ZERO)) freeze(target);
        return admission;
    }

    private void scheduleBounds(TurnSlot turn) {
        timers.bounds(turn, () -> freeze(turn), () -> {
            if (turn.state != TurnState.SENDING) finish(turn, TurnState.EXPIRED, TurnResult.Reason.DEADLINE);
            interrupt(turn);
        });
    }
    private void freeze(TurnSlot turn) {
        turn.frozen = true;
        if (Set.of(TurnState.DECIDING, TurnState.WAITING, TurnState.READY).contains(turn.state)) {
            if (turn.state == TurnState.DECIDING) interrupt(turn);
            makeReady(turn);
        }
    }
    private void makeReady(TurnSlot turn) {
        turn.state = TurnState.READY;
        if (!turn.readyMarked) { turn.readyAt = clock.getAsLong(); turn.readyMarked = true; }
        alarm(turn, turn.readyAt + ai.queueWait().toNanos(), () -> {
            if (turn.state == TurnState.READY) finish(turn, TurnState.EXPIRED, TurnResult.Reason.QUEUE_TIMEOUT);
        });
    }
    private void alarm(TurnSlot turn, long at, Runnable action) {
        timers.alarm(turn, at, action);
    }
    private void cancelAlarm(TurnSlot turn) { timers.cancelAlarm(turn); }
    private void interrupt(TurnSlot turn) {
        if (turn.call != null) notifications.add(turn.call::cancel);
        else if (turn.job != null) {
            var job = turn.job;
            notifications.add(() -> { if (job.cancelQueued() && executor instanceof ThreadPoolExecutor pool) pool.remove(job); });
        }
    }
    private void cancelSession(Session session,boolean interruptSending) {
        if (session == null) return;
        session.closing = true;
        for (var turn : turns(session)) {
            if (!turn.state.terminal() && turn.state != TurnState.SENDING) finish(turn, TurnState.CANCELLED, TurnResult.Reason.CANCELLED);
            if(turn.state!=TurnState.SENDING || interruptSending)interrupt(turn);
        }
    }
    private void finish(TurnSlot turn, TurnState state, TurnResult.Reason reason) {
        if (turn.state.terminal()) return;
        turn.state = state; turn.result = new TurnResult(state, reason); turn.replyToken = null; turn.cancelTimers();
        turn.members.forEach(TurnBuffer.Membership::close); turn.members.clear();
    }
    private boolean advance() {
        boolean changed = false;
        for (var session : List.copyOf(sessions.values())) {
            if (session.pending != null && publish(session.pending)) { session.pending = null; changed = true; }
            if (session.active != null && publish(session.active)) {
                ready.remove(session); session.queued = false; session.active = session.pending; session.pending = null; changed = true;
            }
            if (session.active == null) { ready.remove(session); session.queued = false; sessions.remove(session.user); changed = true; continue; }
            var turn = session.active;
            if (!turn.inFlight && !turn.state.terminal() && (turn.state == TurnState.READY || turn.state == TurnState.DECIDING) && !session.queued) {
                int available = Math.max(0, ai.workers() - running);
                if (kind(turn) == TurnTicket.Kind.DECISION) available = Math.min(available, Math.max(0, decision.maxConcurrent() - deciding));
                if (ready.size() >= ai.queueCapacity() + available) { finish(turn, TurnState.REJECTED, TurnResult.Reason.CAPACITY); changed = true; }
                else { ready.addLast(session); session.queued = true; }
            }
        }
        return changed;
    }
    private boolean publish(TurnSlot turn) {
        if (!turn.state.terminal() || turn.inFlight) return false;
        if (!turn.published) { turn.published = true; turn.completions.values().forEach(future -> notifications.add(() -> future.complete(turn.result))); }
        return true;
    }
    private TurnTicket.Kind kind(TurnSlot turn) {
        if (turn.state == TurnState.READY) return TurnTicket.Kind.GENERATION;
        if (turn.decisions >= decision.maxAttempts()) turn.fallback = true;
        return turn.fallback ? TurnTicket.Kind.RULE : TurnTicket.Kind.DECISION;
    }
    private void dispatch() {
        while (running < ai.workers() && !ready.isEmpty()) {
            Session chosen = ready.stream().filter(session -> session.active.state == TurnState.READY).findFirst()
                    .orElseGet(() -> ready.stream().filter(session -> kind(session.active) != TurnTicket.Kind.DECISION || deciding < decision.maxConcurrent()).findFirst().orElse(null));
            if (chosen == null) return;
            ready.remove(chosen); chosen.queued = false; TurnSlot turn = chosen.active;
            if (turn.state.terminal() || turn.inFlight) continue;
            TurnTicket.Kind type = kind(turn); turn.inFlight = true; running++; if (type == TurnTicket.Kind.DECISION) deciding++;
            var job = new TurnJob(() -> run(turn, type), () -> control(() -> { ended(turn, type, null, false); return null; }));
            turn.job = job;
            notifications.add(() -> {
                try { executor.execute(job); }
                catch (RejectedExecutionException failure) { control(() -> { finish(turn, TurnState.REJECTED, TurnResult.Reason.CLOSED); notifications.add(job::cancelQueued); return null; }); }
            });
        }
    }
    private TurnSlot.Call begin(TurnSlot turn, TurnTicket.Kind type) {
        if (!live(turn) || turn.state.terminal() || sessions.get(turn.user).closing || kind(turn) != type) return null;
        if (!turn.budget.totalDeadline().permits(ai.sendReserve())) { finish(turn, TurnState.EXPIRED, TurnResult.Reason.DEADLINE); return null; }
        if (type != TurnTicket.Kind.GENERATION && !turn.budget.collectionDeadline().permits(Duration.ZERO)) { freeze(turn); return null; }
        if (type == TurnTicket.Kind.GENERATION && (turn.generations >= aggregation.maxGenerationAttempts()
                || clock.getAsLong() - turn.readyAt >= ai.queueWait().toNanos())) {
            finish(turn, TurnState.EXPIRED, TurnResult.Reason.QUEUE_TIMEOUT); return null;
        }
        var call = turn.start(type, generationLimit, decision, clock, buffer);
        Deadline deadline = call.deadline;
        alarm(turn, deadline.expiresNanos(), () -> {
            if (turn.state == TurnState.DECIDING) { turn.fallback = true; }
            else if (turn.state == TurnState.GENERATING) finish(turn, TurnState.FAILED, TurnResult.Reason.DEADLINE);
            interrupt(turn);
        });
        return call;
    }
    private void run(TurnSlot turn, TurnTicket.Kind type) {
        TurnExecution.execute(() -> control(() -> begin(turn, type)),
                call -> control(() -> current(call.ticket) != null), work,
                (call, failed) -> control(() -> { ended(turn, type, call, failed); return null; }));
    }
    private void ended(TurnSlot turn, TurnTicket.Kind type, TurnSlot.Call call, boolean failed) {
        running--; if (type == TurnTicket.Kind.DECISION) deciding--; turn.inFlight = false;
        if (failed && call == null && !turn.state.terminal()) finish(turn, TurnState.FAILED, TurnResult.Reason.WORK_FAILURE);
        if (!turn.state.terminal() && call != null) {
            if (turn.state == TurnState.SENDING) finish(turn, TurnState.UNKNOWN, TurnResult.Reason.SEND_UNKNOWN);
            else if (turn.state == TurnState.GENERATING) finish(turn, TurnState.FAILED, failed ? TurnResult.Reason.WORK_FAILURE : TurnResult.Reason.UNFINISHED_WORK);
            else if (turn.state == TurnState.DECIDING && !call.answered && (failed || current(call.ticket) != null)) turn.fallback = true;
        }
        turn.call = null;
        turn.job = null;
    }
    private TurnSlot current(TurnTicket ticket) {
        TurnSlot turn = find(ticket);
        return turn != null && turn.call != null && turn.call.ticket.equals(ticket) && turn.inputVersion == ticket.inputVersion()
                && turn.generationVersion == ticket.generationVersion() && !turn.state.terminal() ? turn : null;
    }
    private TurnSlot find(TurnTicket ticket) {
        if (ticket == null) return null;
        TurnSlot turn = find(ticket.userId(), ticket.turnId());
        return turn != null && turn.epoch == ticket.epoch() ? turn : null;
    }
    private TurnSlot find(String user, UUID id) {
        Session session = sessions.get(user);
        if (session == null) return null;
        return turns(session).stream().filter(turn -> turn.id.equals(id)).findFirst().orElse(null);
    }
    private boolean live(TurnSlot turn) { return find(turn.user, turn.id) == turn; }
    private List<TurnSlot> turns(Session session) {
        var turns = new ArrayList<TurnSlot>(); if (session.active != null) turns.add(session.active); if (session.pending != null) turns.add(session.pending); return turns;
    }
    private <T> T control(Supplier<T> action) {
        T result; List<Runnable> callbacks;
        synchronized (gate) { result = action.get(); while (advance()) { /* 每次只终结或晋升有界轮次，不产生新任务。 */ } dispatch(); callbacks = List.copyOf(notifications); notifications.clear(); }
        callbacks.forEach(Runnable::run); return result;
    }

}
