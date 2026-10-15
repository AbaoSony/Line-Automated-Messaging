package com.example.line.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 对话历史的只读记录，由 ChatHistoryService 返回；content 保留完整业务数据，不包含事件凭证。
 * @param id 数据库消息主键
 * @param userId 原始用户标识
 * @param direction INBOUND 或 OUTBOUND
 * @param lineMessageId 入站消息幂等标识，出站允许为空
 * @param messageType 消息类型
 * @param content 文本与非文本元数据
 * @param modelName 后续模型名称，当前允许为空
 * @param inputTokens 后续输入用量
 * @param outputTokens 后续输出用量
 * @param latencyMs 后续生成耗时
 * @param createdAt 消息发生时间
 */
public record ChatMessage(long id, String userId, String direction, String lineMessageId,
                          String messageType, JsonNode content, String modelName, Integer inputTokens,
                          Integer outputTokens, Long latencyMs, Instant createdAt) {}
