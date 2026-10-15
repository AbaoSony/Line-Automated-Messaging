package com.example.line.chat;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** 查询最近消息并以时间正序返回，供后续上下文模块使用，本阶段不接入 AI。 */
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
}
