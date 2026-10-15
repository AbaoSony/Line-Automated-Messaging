package com.example.line.ai.coordination;

import com.example.line.user.UserDeletionGuard;
import org.springframework.stereotype.Component;

/** 接入统一用户删除，取消尚未执行的 AI 工作并立即释放它们的跟踪租约。 */
@Component
class AiDeletionGuard implements UserDeletionGuard {
    private final SessionScheduler scheduler;
    AiDeletionGuard(SessionScheduler scheduler){this.scheduler=scheduler;}
    /**
     * 取消当前用户尚未开始的短时会话，不发送删除前的旧提示。
     * @param userId 完整用户标识
     */
    @Override public void cancelPending(String userId){scheduler.cancel(userId);}
}
