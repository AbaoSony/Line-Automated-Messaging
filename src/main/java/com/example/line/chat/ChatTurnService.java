package com.example.line.chat;

import com.example.line.common.logging.LogEvents;
import com.example.line.common.persistence.AsyncPersistence;
import com.example.line.user.UserDataPurger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 将成功出站、轮次和成员作为单个异步业务事务提交，失败仅记录，不重新触发发送。 */
@Service
public class ChatTurnService implements UserDataPurger {
    private final ChatTurnRepository repository;
    private final AsyncPersistence persistence;
    private final TransactionTemplate transaction;
    /**
     * 注入完整轮次仓储、原有有界落库器和业务事务管理器。
     * @param repository 成功轮次仓储
     * @param persistence 有界异步落库及删除屏障
     * @param manager 业务 PostgreSQL 事务
     */
    public ChatTurnService(ChatTurnRepository repository,AsyncPersistence persistence,PlatformTransactionManager manager) {
        this.repository=repository;this.persistence=persistence;this.transaction=new TransactionTemplate(manager);
    }
    /**
     * 仅在 LINE 明确接受正常 AI 回复后调用；快照不引用协调器或凭证。
     * @param turn 完整不可变已接受轮次
     * @throws NullPointerException 快照为空时抛出
     */
    public void recordAccepted(AcceptedTurn turn) {
        java.util.Objects.requireNonNull(turn);
        persistence.submit(LogEvents.LINE_REPLY_SENT,turn.userId(),() -> transaction.executeWithoutResult(status -> repository.save(turn)));
    }
    /**
     * 删除本模块轮次及级联成员，作为统一用户删除事务的一部分，可重复执行。
     * @param userId 完整用户标识
     */
    @Override public void purge(String userId) { repository.delete(userId); }
}
