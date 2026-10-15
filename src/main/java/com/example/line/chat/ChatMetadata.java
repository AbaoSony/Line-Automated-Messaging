package com.example.line.chat;

import java.time.Instant;

/** 为新消息标记来源与关联，旧数据保持 LEGACY/UNKNOWN，不用相邻记录猜测轮次。 */
public record ChatMetadata(String sourceType, String conversationId, String replyKind, String inReplyToMessageId,
                           String correlationId, Instant receivedAt, long receivedSequence,
                           String model, Integer inputTokens, Integer outputTokens, Long latencyMs) {}
