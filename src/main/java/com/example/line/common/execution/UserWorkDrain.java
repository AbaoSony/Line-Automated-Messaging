package com.example.line.common.execution;

import com.example.line.common.persistence.*;
import com.example.line.common.logging.*;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 上游停机后、持久化池关闭前核实用户工作实际排空；资源无法清理时明确报告，不承诺重启恢复。 */
@Component
class UserWorkDrain implements SmartLifecycle {
    private final UserWorkTracker tracker;
    private final PersistenceProperties properties;
    private boolean running=true;
    UserWorkDrain(UserWorkTracker tracker,PersistenceProperties properties){this.tracker=tracker;this.properties=properties;}
    /** 无需启动独立线程。 */
    @Override public void start(){}
    /** @return 是否尚未执行关闭检查 */
    @Override public boolean isRunning(){return running;}
    /** @return 位于 AI 停止与业务持久化关闭之间 */
    @Override public int getPhase(){return DrainingTaskExecutor.PERSISTENCE_PHASE+1;}
    /**
     * 等待真实工作退出，超时不能伪造成功排空。
     * @throws IllegalStateException 用户工作未排空时抛出
     */
    @Override public void stop(){
        running=false;
        try{tracker.awaitAll(properties.shutdownTimeout());}
        catch(IllegalStateException failure){
            BusinessLog.failure(LoggerFactory.getLogger(getClass()),Level.ERROR,LogEvents.EXECUTOR_REJECTED,
                    "User work shutdown did not drain",failure,BusinessLog.fields("reason","shutdown_not_drained","outcome","FAILED"));
            throw failure;
        }
    }
}
