package com.example.line.ai.aggregation;

import java.time.Instant;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.example.line.common.logging.LogData;
import com.linecorp.bot.jackson.ModelObjectMapper;

/** 保留一条私聊原文及原始追踪/排序边界，不接收 SDK 对象或任何发送凭证。 */
public record InboundMember(String userId, String eventId, String messageId, String text,
                            Instant occurredAt, Instant receivedAt, long sequence, String traceId, JsonNode content) {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=ModelObjectMapper.createNewObjectMapper();
    /**
     * 为仅含文本的调用方建立完整文本业务快照。
     * @param userId 用户标识
     * @param eventId 事件标识
     * @param messageId 消息标识
     * @param text 完整原文
     * @param occurredAt 事件时间
     * @param receivedAt 接收时间
     * @param sequence 接收序号
     * @param traceId 原追踪标识
     */
    public InboundMember(String userId,String eventId,String messageId,String text,Instant occurredAt,
                         Instant receivedAt,long sequence,String traceId) {
        this(userId,eventId,messageId,text,occurredAt,receivedAt,sequence,traceId,
                JsonNodeFactory.instance.objectNode().put("type","text").put("id",messageId).put("text",text));
    }
    /**
     * 校验准入前已完成来源识别的文本业务数据，不改变原文。
     * @param userId 完整用户标识
     * @param eventId 事件标识
     * @param messageId 消息标识
     * @param text 完整原文
     * @param occurredAt 原事件时间
     * @param receivedAt 原接收时间
     * @param sequence 原接收序号
     * @param traceId 原追踪标识
     * @param content 完整允许业务元数据
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
        content=MAPPER.valueToTree(LogData.business(Objects.requireNonNull(content,"content")));
    }
    /** @return 独立业务副本，防止调用方改变已冻结的原始数据 */
    @Override public JsonNode content() { return content.deepCopy(); }
}
