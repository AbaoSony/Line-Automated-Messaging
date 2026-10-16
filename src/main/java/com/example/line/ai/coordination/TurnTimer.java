package com.example.line.ai.coordination;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 可替换的绝对单调时限定时器；测试可推进时钟，不通过睡眠模拟语义等待。 */
@FunctionalInterface
public interface TurnTimer {
    /**
     * 登记一次轻量控制回调，禁止在回调中执行模型、发送或同步日志写库。
     * @param atNanos 原始单调时钟域中的截止
     * @param action 控制事件
     * @return 可以幂等取消并移除队列引用的句柄
     * @throws java.util.concurrent.RejectedExecutionException 定时设施已经停止时抛出
     */
    Cancellation schedule(long atNanos, Runnable action);

    /** 定时引用释放入口。 */
    @FunctionalInterface
    interface Cancellation {
        /** 幂等取消当前定时，迟到回调仍须由协调器检查版本。 */
        void cancel();
    }

    /**
     * 复用 SessionScheduler 已有定时线程；执行器必须启用取消后移除策略。
     * @param executor 现有定时执行器
     * @param clock 同一单调时钟
     * @return 绝对时限定时器适配
     */
    static TurnTimer using(ScheduledExecutorService executor, LongSupplier clock) {
        return (at, action) -> {
            var future = executor.schedule(action, Math.max(0, at - clock.getAsLong()), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        };
    }
}
