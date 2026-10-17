package com.example.line.user;

import com.example.line.common.logging.LogEvents;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 接收关注生命周期操作并异步更新用户表；取消关注只变更状态，不自动删除数据。 */
@Service
public class UserService implements UserDataPurger {
    private final UserRepository repository;
    private final UserLifecycleQueue queue;
    private final List<UserLifecycleListener> listeners;

    /**
     * 注入用户仓储与失败隔离器。
     * @param repository PostgreSQL 用户仓储
     * @param queue 同用户有界生命周期入口，复用持久化池
     * @param listeners 状态生效后的单向业务通知
     */
    public UserService(UserRepository repository, UserLifecycleQueue queue,List<UserLifecycleListener> listeners) {
        this.repository = repository;
        this.queue=queue;this.listeners=List.copyOf(listeners);
    }

    /**
     * 异步记录关注或取消关注，不存在用户来源时安全跳过。
     * @param userId 完整用户标识，群组来源可能为空
     * @param following 是否处于关注状态
     * @param occurredAt LINE 事件时间，用于阻止旧事件覆盖新状态
     * @throws IllegalArgumentException 事件时间缺失时抛出
     */
    public void recordStatus(String userId, boolean following, Instant occurredAt) {
        if (userId == null || userId.isBlank()) return;
        if (occurredAt == null) throw new IllegalArgumentException("Missing lifecycle timestamp");
        recordStatus(userId,following,LifecycleOrder.current(occurredAt,java.util.UUID.randomUUID().toString(),false));
    }

    /**
     * 在与 AI 准入相同的有界有序边界内更新权威状态，仅有效变更通知业务取消；失败不驱动重投递。
     * @param userId 完整用户标识，缺失时跳过
     * @param following 是否关注
     * @param order 原始接收排序，不含凭证
     * @return 真正更新并完成业务通知时为 true，旧事件、重复、拒绝和失败为 false
     */
    public CompletionStage<Boolean> recordStatus(String userId,boolean following,LifecycleOrder order) {
        if(userId==null || userId.isBlank())return CompletableFuture.completedStage(false);
        return queue.submit(userId,following?LogEvents.USER_FOLLOW:LogEvents.USER_UNFOLLOW,() -> {
            boolean applied=repository.updateStatus(userId,following,order);
            if(applied){if(!following)queue.cancelAi(userId);listeners.forEach(listener -> listener.statusChanged(userId,following));}
            return applied;
        }).exceptionally(failure -> false);
    }

    /**
     * 异步读取权威关注状态后执行短时 AI 准入；同用户后续生命周期不等待模型或网络发送。
     * @param userId 完整用户标识
     * @param deadline 原始 HTTP 截止，排队不延期
     * @param admission 只执行有界短时准入，不得同步等待正文或发送
     * @return 实际 AI 终态；明确取消关注、删除、容量拒绝或依赖失败则静默结束
     */
    public CompletionStage<Void> admitActive(String userId,com.example.line.common.execution.Deadline deadline,
                                            Supplier<CompletionStage<Void>> admission) {
        var held=new java.util.concurrent.atomic.AtomicReference<>(admission);
        return queue.submit(userId,LogEvents.AI_TURN_ADMITTED,() -> {
            boolean active=repository.active(userId);
            var current=held.getAndSet(null);
            if(!active)com.example.line.common.logging.BusinessLog.write(org.slf4j.LoggerFactory.getLogger(getClass()),
                    org.slf4j.event.Level.INFO,LogEvents.AI_REPLY_SKIPPED,"AI admission skipped for unfollowed user",
                    com.example.line.common.logging.BusinessLog.fields("reason","unfollowed","outcome","SKIPPED"));
            if(!active || current==null || !deadline.permits(java.time.Duration.ZERO))return CompletableFuture.<Void>completedStage(null);
            queue.bindAiAdmission(userId);return current.get();
        },deadline,() -> held.set(null))
                .exceptionally(failure -> CompletableFuture.completedStage(null)).thenCompose(stage -> stage);
    }

    /** @return 所有短时状态变更与准入已退出，不表示 AI 模型或发送已经排空 */
    public boolean admissionsIdle(){return queue.isIdle();}

    /**
     * 同步删除本模块的用户状态，供统一手动删除事务调用。
     * @param userId 完整用户标识
     * @throws org.springframework.dao.DataAccessException 数据库操作失败时抛出
     */
    @Override
    public void purge(String userId) { repository.delete(userId); }
}
