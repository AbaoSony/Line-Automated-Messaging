package com.example.line.ai.aggregation;

import java.time.Instant;
import java.util.Objects;

/** 保留一条私聊原文及原始追踪/排序边界，不接收 SDK 对象或任何发送凭证。 */
public record InboundMember(String userId, String eventId, String messageId, String text,
                            Instant occurredAt, Instant receivedAt, long sequence, String traceId) {
    /**
     * 校验准入前已完成来源识别的文本业务数据，不改变原文。
     * @throws IllegalArgumentException 标识缺失或顺序无效时抛出
     * @throws NullPointerException 原文或时间缺失时抛出
     */
    public InboundMember {
        for (String value : new String[]{userId, eventId, messageId, traceId}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing turn member identity");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (sequence < 1) throw new IllegalArgumentException("Invalid turn member sequence");
    }
}
