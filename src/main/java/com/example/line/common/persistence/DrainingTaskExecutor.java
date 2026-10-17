package com.example.line.common.persistence;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 将既有有界执行器的排空提前到生命周期阶段，保证业务任务先结束、事件记录器后刷队列。 */
public final class DrainingTaskExecutor extends ThreadPoolTaskExecutor {
    public static final int PERSISTENCE_PHASE = DEFAULT_PHASE;
    public static final int WEBHOOK_PHASE = PERSISTENCE_PHASE + 5;

    /**
     * 在 Spring 生命周期关闭期间执行既有的有界等待，不延迟到 Bean 销毁后才开始排空。
     * @param callback 当前阶段停止完成通知
     * @throws IllegalStateException 实际工作未退出时抛出，不能把有限等待当作排空成功
     */
    @Override
    public void stop(Runnable callback) {
        try {
            shutdown();
            if(!getThreadPoolExecutor().isTerminated()){
                com.example.line.common.logging.BusinessLog.write(org.slf4j.LoggerFactory.getLogger(getClass()),org.slf4j.event.Level.ERROR,
                        com.example.line.common.logging.LogEvents.EXECUTOR_REJECTED,"Business executor shutdown did not drain",
                        com.example.line.common.logging.BusinessLog.fields("reason","shutdown_not_drained","outcome","FAILED"));
                throw new IllegalStateException("Business executor shutdown did not drain");
            }
        }
        finally { callback.run(); }
    }
}
