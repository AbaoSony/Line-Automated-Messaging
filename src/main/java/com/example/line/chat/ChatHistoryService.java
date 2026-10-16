package com.example.line.chat;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** 提供通用历史及 AI 私聊完整成功轮次；PostgreSQL 权威关联验证全部成员，绝不返回残缺轮次。 */
@Service
@EnableConfigurationProperties(ChatProperties.class)
public class ChatHistoryService {
    private final ChatRepository repository;
    private final ChatProperties properties;

    /**
     * 注入历史仓储和查询上限。
     * @param repository 业务消息仓储
     * @param properties 保留与查询配置
     */
    public ChatHistoryService(ChatRepository repository, ChatProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 查询最新 limit 条消息，同时间按数据库主键稳定排序，结果按时间正序返回。
     * @param userId 非空白完整用户标识
     * @param limit 请求条数，范围为 1 至配置的 history-max-limit
     * @return 最新消息的不可变列表，无记录时为空
     * @throws IllegalArgumentException 用户标识为空或查询条数越界时抛出
     * @throws org.springframework.dao.DataAccessException 查询失败时抛出
     */
    public List<ChatMessage> findRecent(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit < 1 || limit > properties.historyMaxLimit()) {
            throw new IllegalArgumentException("Invalid userId or chat history limit");
        }
        return List.copyOf(repository.findRecent(userId, limit));
    }

    /**
     * 查询快照之前的有界完整私聊轮次，验证成员连续性并排除旧未知来源、全部当前和后续消息。
     * @param userId 完整私聊用户
     * @param currentId 当前入站消息 ID
     * @param receivedAt 当前接收时间
     * @param sequence 同时刻接收顺序
     * @param scanLimit 消息预算，每轮全部成员加唯一回复一起计数，不代表轮数
     * @return 完整轮次与上一条已知私聊入站时间
     * @throws IllegalArgumentException 查询边界无效时抛出
     * @throws org.springframework.dao.DataAccessException 读取失败时抛出
     */
    public PrivateHistory findPrivate(String userId, String currentId, java.time.Instant receivedAt, long sequence, int scanLimit) {
        if (userId == null || userId.isBlank() || currentId == null || receivedAt == null || scanLimit < 1 || scanLimit > 1000)
            throw new IllegalArgumentException("Invalid private history boundary");
        return repository.privateHistory(userId, currentId, receivedAt, sequence, scanLimit);
    }

    /**
     * 查询当前边界之前最近的私聊入站时间，包含非文本与未形成正常轮次的消息。
     * @param userId 完整私聊用户
     * @param currentId 当前消息 ID
     * @param receivedAt 接收边界
     * @param sequence 接收序号
     * @return 最近时间，无历史时为 null
     * @throws org.springframework.dao.DataAccessException 数据库查询失败时抛出
     */
    public PrivateHistory.InputTime lastPrivateInbound(String userId,String currentId,java.time.Instant receivedAt,long sequence) {
        return repository.lastPrivateInbound(userId,currentId,receivedAt,sequence);
    }
}
