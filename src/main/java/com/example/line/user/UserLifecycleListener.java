package com.example.line.user;

/** 有效关注状态已落库后通知业务取消未发送任务；用户模块不反向依赖 AI。 */
@FunctionalInterface
public interface UserLifecycleListener {
    /**
     * 在同用户有序准入边界内应用已确认的状态，后续消息不能越过本次通知。
     * @param userId 完整用户标识
     * @param following 是否关注
     */
    void statusChanged(String userId,boolean following);
}
