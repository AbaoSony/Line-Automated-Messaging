package com.example.line.ai.memory;

import com.example.line.chat.PrivateHistory;
import java.time.Instant;
import java.util.List;

/** 为会话编排隔离 Redis 热缓存和 PostgreSQL 回源，所有记录只包含业务数据。 */
public interface ConversationMemory {
    /**
     * 读取当前消息之前的有界快照，依赖失败通过异常表达。
     * @param user 完整用户标识
     * @param messageId 当前入站消息 ID
     * @param received 接收时间边界
     * @param sequence 同时刻接收顺序
     * @return 正常完整轮次及已知上一条入站时间
     * @throws RuntimeException Redis 或数据库不可用时抛出
     */
    PrivateHistory load(String user, String messageId, Instant received, long sequence, String owner,
                        com.example.line.common.execution.Deadline deadline);
    /**
     * 保存 LINE 已接受的一轮，缓存提交必须原子验证锁持有权。
     * @param user 完整用户
     * @param owner 当前锁持有者
     * @param history 当前快照加已发送轮次
     * @throws RuntimeException 依赖失败或锁失效时抛出
     */
    void save(String user, String owner, PrivateHistory history);
    /**
     * LINE 明确接受后立即保留有界本地业务轮次，衔接异步数据库和缓存失败的间隙。
     * @param user 完整用户标识
     * @param turn 全部成员及已接受正文，不允许传入草稿或技术提示
     */
    void rememberAccepted(String user,PrivateHistory.Turn turn);
    /**
     * 记录已知私聊入站时间，包含非文本/提示，不把它们作为正常模型轮次。
     * @param user 完整用户
     * @param id 入站消息 ID
     * @param occurred 消息事件时间
     * @param received 接收边界
     * @param sequence 接收顺序
     */
    void observe(String user, String id, Instant occurred, Instant received, long sequence);
}
