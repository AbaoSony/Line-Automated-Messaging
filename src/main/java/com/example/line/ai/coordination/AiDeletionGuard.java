package com.example.line.ai.coordination;

import com.example.line.user.UserDeletionGuard;
import org.springframework.stereotype.Component;

/** 接入统一用户删除；逐条模式取消等待任务，轮次模式失效未发送工作并请求在途取消。 */
@Component
class AiDeletionGuard implements UserDeletionGuard {
    private final SessionScheduler scheduler;
    AiDeletionGuard(SessionScheduler scheduler){this.scheduler=scheduler;}
    /**
     * 取消当前用户未发送工作；实际在途执行仍须由统一删除屏障在事务外等待，不假报排空。
     * @param userId 完整用户标识
     */
    @Override public void cancelPending(String userId){scheduler.cancel(userId);}
}
