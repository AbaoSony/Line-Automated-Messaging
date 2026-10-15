package com.example.line.common.execution;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.slf4j.MDC;

/** 使用单调时钟共享入口截止时间，排队、依赖、模型和发送不能各自重置完整预算。 */
public record Deadline(long startedNanos, long expiresNanos, LongSupplier clock) {
    /**
     * 从 MDC 中的 HTTP 接收时间创建截止时间，后台调用从当前时刻开始。
     * @param budget 总处理预算
     * @return 贯穿全部步骤的截止时间
     */
    public static Deadline current(Duration budget) {
        String received = MDC.get("received_nanos");
        return from(received == null ? System.nanoTime() : Long.parseLong(received), budget, System::nanoTime);
    }

    /**
     * 为生产和可控时钟测试构造同一截止时间。
     * @param start 接收时刻的单调计数
     * @param budget 总预算
     * @param clock 单调时钟
     * @return 截止时间
     */
    public static Deadline from(long start, Duration budget, LongSupplier clock) {
        return new Deadline(start, start + budget.toNanos(), clock);
    }

    /** @return 当前剩余预算，过期返回零 */
    public Duration remaining() { return Duration.ofNanos(Math.max(0, expiresNanos - clock.getAsLong())); }

    /**
     * 取阶段上限与剩余预算的较小值。
     * @param cap 阶段上限
     * @return 当前允许等待时间
     */
    public Duration limit(Duration cap) { return remaining().compareTo(cap) < 0 ? remaining() : cap; }

    /**
     * 检查是否还可以保留后续发送预算。
     * @param reserve 后续所需时间
     * @return 剩余时间严格大于预留时为 true
     */
    public boolean permits(Duration reserve) { return remaining().compareTo(reserve) > 0; }

    /** @return 从原始接收开始的已耗时毫秒数 */
    public long elapsedMillis() { return Duration.ofNanos(Math.max(0, clock.getAsLong() - startedNanos)).toMillis(); }
}
