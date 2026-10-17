package com.example.line.ai.coordination;

import com.example.line.user.UserDeletionGuard;
import org.springframework.stereotype.Component;

/** 接入统一用户删除和有效取消关注；旧状态不通知，未发送任务失效，实际在途工作继续计数。 */
@Component
class AiDeletionGuard implements UserDeletionGuard,com.example.line.user.UserLifecycleListener {
    private final SessionScheduler scheduler;
    AiDeletionGuard(SessionScheduler scheduler){this.scheduler=scheduler;}
    /**
     * 取消当前用户未发送工作；实际在途执行仍须由统一删除屏障在事务外等待，不假报排空。
     * @param userId 完整用户标识
     */
    @Override public void cancelPending(String userId){scheduler.cancel(userId);}
    /**
     * 只对权威排序确认的取消关注取消未发送任务，关注事件本身不启动或恢复旧任务。
     * @param userId 完整用户标识
     * @param following 已确认关注状态
     */
    @Override public void statusChanged(String userId,boolean following){if(!following)scheduler.cancel(userId);}
}
