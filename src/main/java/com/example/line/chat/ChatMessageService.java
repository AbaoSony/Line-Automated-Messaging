package com.example.line.chat;

import com.example.line.common.persistence.AsyncPersistence;
import com.example.line.common.logging.LogEvents;
import com.example.line.user.UserDataPurger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 提供独立异步入站/出站记录入口，保存完整业务内容；凭证字段在提交前移除，不进入队列或数据库。 */
@Service
public class ChatMessageService implements UserDataPurger {
    private static final Set<String> CREDENTIAL_FIELDS = Set.of("replytoken", "quotetoken", "markasreadtoken",
            "channelaccesstoken", "channeltoken", "channelsecret", "accesstoken", "apikey", "password",
            "signature", "xlinesignature", "authorization", "token", "secret");
    private final ChatRepository repository;
    private final AsyncPersistence persistence;

    /**
     * 注入对话仓储与异步落库入口。
     * @param repository 业务 PostgreSQL 仓储
     * @param persistence 独立失败隔离与线程池
     */
    public ChatMessageService(ChatRepository repository, AsyncPersistence persistence) {
        this.repository = repository;
        this.persistence = persistence;
    }

    /**
     * 异步保存一条入站消息，重复消息 ID 由 PostgreSQL 唯一索引安全忽略。
     * @param userId 原始用户标识，来源不提供时跳过
     * @param lineMessageId 原始入站消息 ID
     * @param messageType 消息类型
     * @param content 完整业务内容，包括文本与元数据，不含二进制
     * @param occurredAt LINE 消息时间
     * @throws IllegalArgumentException 内容、类型或时间缺失时抛出
     */
    public void recordInbound(String userId, String lineMessageId, String messageType,
                              JsonNode content, Instant occurredAt) {
        if (userId == null || userId.isBlank()) return;
        if (lineMessageId == null || lineMessageId.isBlank()) {
            // 存储所需 ID 缺失不能改变既有 Reply 行为，仍走异步失败记录与指标。
            persistence.submit(LogEvents.USER_MESSAGE_RECEIVED, userId,
                    () -> { throw new IllegalArgumentException("Missing message ID"); });
            return;
        }
        record(userId, "INBOUND", lineMessageId, messageType, content, occurredAt, LogEvents.USER_MESSAGE_RECEIVED);
    }

    /**
     * 异步保存成功发送的出站业务内容，供当前 echo 与后续模块调用；不得传入 Reply Token。
     * @param userId 原始用户标识，来源不提供时跳过
     * @param messageType 消息类型
     * @param content 完整业务内容，模型字段本阶段留空
     * @throws IllegalArgumentException 内容或类型缺失时抛出
     */
    public void recordOutbound(String userId, String messageType, JsonNode content) {
        if (userId == null || userId.isBlank()) return;
        record(userId, "OUTBOUND", null, messageType, content, Instant.now(), LogEvents.LINE_REPLY_SENT);
    }

    /**
     * 异步保存含来源、关联和生成用量的新入站或已接受出站；不传入事件凭证。
     * @param userId 完整用户
     * @param direction INBOUND / OUTBOUND
     * @param messageId 入站幂等消息 ID，出站为空
     * @param type 消息类型
     * @param content 完整业务内容
     * @param occurredAt 事件/发送时间
     * @param metadata 来源、轮次关联与生成用量
     * @throws IllegalArgumentException 必需字段缺失时抛出
     */
    public void recordMessage(String userId, String direction, String messageId, String type, JsonNode content,
                              Instant occurredAt, ChatMetadata metadata) {
        if (userId == null || userId.isBlank()) return;
        if("INBOUND".equals(direction) && (messageId==null || messageId.isBlank())) {
            persistence.submit(LogEvents.USER_MESSAGE_RECEIVED,userId,() -> {throw new IllegalArgumentException("Missing message ID");});return;
        }
        if (content == null || occurredAt == null || metadata == null || type == null)
            throw new IllegalArgumentException("Missing conversation metadata");
        JsonNode snapshot = content.deepCopy();
        removeCredentials(snapshot);
        String json = snapshot.toString();
        persistence.submit("INBOUND".equals(direction) ? LogEvents.USER_MESSAGE_RECEIVED : LogEvents.LINE_REPLY_SENT,
                userId, () -> repository.insert(userId, direction, messageId, type, json, occurredAt, metadata));
    }

    /**
     * 删除本模块的全部用户对话记录，供显式手动删除事务调用。
     * @param userId 完整用户标识
     * @throws org.springframework.dao.DataAccessException 数据库操作失败时抛出
     */
    @Override
    public void purge(String userId) { repository.delete(userId); }

    private void record(String userId, String direction, String messageId, String type,
                        JsonNode content, Instant time, String operation) {
        if (type == null || type.isBlank() || content == null || time == null) {
            throw new IllegalArgumentException("Missing chat message type, content or timestamp");
        }
        // 不保留原事件对象；SDK 的 quoteToken、markAsReadToken 也属于不可存储的凭证。
        JsonNode snapshot = content.deepCopy();
        removeCredentials(snapshot);
        String json = snapshot.toString();
        persistence.submit(operation, userId,
                () -> repository.insert(userId, direction, messageId, type, json, time));
    }

    private void removeCredentials(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String normalized = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                if (CREDENTIAL_FIELDS.contains(normalized) || normalized.endsWith("token")) object.remove(name);
                else removeCredentials(object.get(name));
            }
        } else if (node.isArray()) {
            node.forEach(this::removeCredentials);
        }
    }
}
