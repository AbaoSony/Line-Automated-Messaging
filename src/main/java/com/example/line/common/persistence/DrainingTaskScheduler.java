package com.example.line.common.persistence;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 在上游关闭前结束定时保留清理的实际执行；避免仅暂停调度、延后到日志刷出之后才销毁。 */
final class DrainingTaskScheduler extends ThreadPoolTaskScheduler {
    /**
     * 取消后续定时并执行配置的有限等待，后台清理日志必须在事件记录器关闭前产生。
     * @param callback 当前阶段停止通知
     * @throws IllegalStateException 清理实际工作在预算内无法退出时抛出
     */
    @Override public void stop(Runnable callback){
        try{shutdown();if(!getScheduledThreadPoolExecutor().isTerminated()){
            com.example.line.common.logging.BusinessLog.write(org.slf4j.LoggerFactory.getLogger(getClass()),org.slf4j.event.Level.ERROR,
                    com.example.line.common.logging.LogEvents.EXECUTOR_REJECTED,"Scheduled cleanup shutdown did not drain",
                    com.example.line.common.logging.BusinessLog.fields("reason","shutdown_not_drained","outcome","FAILED"));
            throw new IllegalStateException("Scheduled cleanup shutdown did not drain");
        }}finally{callback.run();}
    }
}
