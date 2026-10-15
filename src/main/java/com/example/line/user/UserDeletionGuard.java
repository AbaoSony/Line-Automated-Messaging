package com.example.line.user;

/** 在数据库删除事务之前通知业务模块取消用户排队工作，不让 user 反向依赖 AI 模块。 */
@FunctionalInterface
public interface UserDeletionGuard {
    /**
     * 用户屏障建立后取消待处理任务，正在执行的任务由公共工作跟踪器等待。
     * @param userId 完整用户标识
     */
    void cancelPending(String userId);
}
