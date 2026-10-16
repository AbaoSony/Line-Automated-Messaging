package com.example.line.ai.aggregation;

import com.example.line.common.execution.Deadline;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 计算 P2 轮次不可延后的总、收集和正文截止，与公共 Deadline 共享单调时钟。
 * 对象不可变，不持有用户内容或凭证；调用方负责保存新快照并执行状态和发送权校验。
 */
public final class TurnBudget {
    private static final Duration MAX_LIFETIME = Duration.ofSeconds(50);
    private final long startedNanos;
    private final long expiresNanos;
    private final long collectionExpiresNanos;
    private final Long generationExpiresNanos;
    private final long collectionReserveNanos;
    private final long sendReserveNanos;
    private final LongSupplier clock;

    private TurnBudget(long startedNanos, long expiresNanos, long collectionExpiresNanos,
                       Long generationExpiresNanos, long collectionReserveNanos,
                       long sendReserveNanos, LongSupplier clock) {
        this.startedNanos = startedNanos;
        this.expiresNanos = expiresNanos;
        this.collectionExpiresNanos = collectionExpiresNanos;
        this.generationExpiresNanos = generationExpiresNanos;
        this.collectionReserveNanos = collectionReserveNanos;
        this.sendReserveNanos = sendReserveNanos;
        this.clock = clock;
    }

    /**
     * 从首条原始入口截止建立预算，前置耗时已包含，不从调用时刻重新计时。
     * @param first 首条 HTTP 入口的截止与时钟
     * @param properties 收集跨度及各阶段预留
     * @param sendReserve 当前 AI 配置中的发送预留
     * @return 固定截止的轮次预算，预留不足时收集窗口压缩为零
     * @throws IllegalArgumentException 总预算或发送预留不合法时抛出
     * @throws NullPointerException 必需参数或时钟为空时抛出
     */
    public static TurnBudget create(Deadline first, AggregationProperties properties, Duration sendReserve) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(sendReserve, "sendReserve");
        Objects.requireNonNull(first.clock(), "clock");
        long lifetime = first.expiresNanos() - first.startedNanos();
        if (lifetime <= 0 || lifetime > MAX_LIFETIME.toNanos() || sendReserve.isNegative()
                || sendReserve.isZero() || sendReserve.compareTo(MAX_LIFETIME) > 0
                || sendReserve.toNanos() >= lifetime) {
            throw new IllegalArgumentException("Turn lifetime must exceed send reserve and not exceed 50s");
        }
        long reserve = properties.queueReserve().plus(properties.dependencyReserve())
                .plus(properties.generationReserve()).plus(sendReserve).toNanos();
        long collection = Math.max(first.startedNanos(), Math.min(
                first.startedNanos() + properties.collectionSpan().toNanos(), first.expiresNanos() - reserve));
        return new TurnBudget(first.startedNanos(), first.expiresNanos(), collection,
                null, reserve, sendReserve.toNanos(), first.clock());
    }

    /**
     * 合入一个成员的原始截止，只可缩短已有截止；更新令牌或晋升轮次不能获得新预算。
     * @param member 已准入成员的原始入口截止，使用同一单调时钟域
     * @return 各阶段均不晚于当前对象的新预算
     * @throws NullPointerException 成员截止为空时抛出
     */
    public TurnBudget including(Deadline member) {
        Objects.requireNonNull(member, "member");
        long end = Math.min(expiresNanos, member.expiresNanos());
        long collection = Math.min(end, Math.min(collectionExpiresNanos,
                Math.max(startedNanos, end - collectionReserveNanos)));
        Long generation = generationExpiresNanos == null ? null
                : Long.valueOf(Math.min(generationExpiresNanos, end - sendReserveNanos));
        return new TurnBudget(startedNanos, end, collection, generation,
                collectionReserveNanos, sendReserveNanos, clock);
    }

    /**
     * 首次生成时冻结正文截止，重试或重生成重复调用时复用原截止。
     * @param stageLimit 模型阶段上限，不代表每次请求能独占该时长
     * @return 已冻结正文截止的新预算；已经开始生成时返回当前预算
     * @throws IllegalArgumentException 阶段上限不大于零或超过 50 秒时抛出
     * @throws IllegalStateException 当前已无法保留发送预算时抛出
     */
    public TurnBudget beginGeneration(Duration stageLimit) {
        if (stageLimit == null || stageLimit.isNegative() || stageLimit.isZero()
                || stageLimit.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("Generation stage must be positive and not exceed 50s");
        }
        if (generationExpiresNanos != null) return this;
        long now = clock.getAsLong();
        if (expiresNanos - now <= sendReserveNanos) {
            throw new IllegalStateException("Insufficient turn budget before generation");
        }
        long end = Math.min(expiresNanos - sendReserveNanos, now + stageLimit.toNanos());
        return new TurnBudget(startedNanos, expiresNanos, collectionExpiresNanos, end,
                collectionReserveNanos, sendReserveNanos, clock);
    }

    /** @return 从首条入口起计算的总截止，可供协调与发送共享 */
    public Deadline totalDeadline() { return new Deadline(startedNanos, expiresNanos, clock); }

    /** @return 不因新输入、晋升或回退延后的收集截止 */
    public Deadline collectionDeadline() { return new Deadline(startedNanos, collectionExpiresNanos, clock); }

    /**
     * 读取所有正文重试与重生成共用的截止。
     * @return 已冻结的正文阶段截止
     * @throws IllegalStateException 尚未开始正文生成时抛出
     */
    public Deadline generationDeadline() {
        if (generationExpiresNanos == null) throw new IllegalStateException("Generation has not started");
        return new Deadline(startedNanos, generationExpiresNanos, clock);
    }
}
