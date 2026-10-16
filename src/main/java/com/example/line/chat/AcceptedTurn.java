package com.example.line.chat;

import com.example.line.common.logging.LogData;
import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.bot.jackson.ModelObjectMapper;
import java.time.Instant;
import java.util.*;

/** LINE 接受后脱离协调器作用域的业务快照，供异步事务与已接受缓存使用，不接收发送凭证。 */
public record AcceptedTurn(UUID turnId,UUID sendAttemptId,String userId,List<Input> members,
                           String assistantText,ChatMetadata metadata,Instant acceptedAt) {
    /**
     * 验证已接受轮次的身份、完整成员及原始边界，避免异步持有不完整对象。
     * @param turnId 会话所有者生成的轮次标识
     * @param sendAttemptId 会话所有者生成的发送标识
     * @param userId 完整用户标识
     * @param members 全部有序原始成员
     * @param assistantText 已获接受的正常正文
     * @param metadata 来源、首条边界及实际模型用量
     * @param acceptedAt 明确接受时间
     * @throws IllegalArgumentException 身份、成员顺序或正常回复元数据无效时抛出
     */
    public AcceptedTurn {
        Objects.requireNonNull(turnId);Objects.requireNonNull(sendAttemptId);Objects.requireNonNull(acceptedAt);
        members=List.copyOf(members);
        if(userId==null || userId.isBlank() || members.isEmpty() || assistantText==null || assistantText.isBlank()
                || metadata==null || !"USER".equals(metadata.sourceType()) || !userId.equals(metadata.conversationId())
                || !"AI".equals(metadata.replyKind()))throw new IllegalArgumentException("Invalid accepted turn");
        var ids=new HashSet<String>(); Input previous=null;
        for(var member:members) {
            if(!ids.add(member.messageId()) || previous!=null && (previous.receivedAt().isAfter(member.receivedAt())
                    || previous.receivedAt().equals(member.receivedAt()) && previous.sequence()>=member.sequence()))
                throw new IllegalArgumentException("Invalid accepted member order");
            previous=member;
        }
        var first=members.get(0);
        if(!first.eventId().equals(metadata.correlationId()) || !first.receivedAt().equals(metadata.receivedAt())
                || first.sequence()!=metadata.receivedSequence())throw new IllegalArgumentException("Invalid accepted boundary");
    }
    /** @return 不含业务凭证的完整已接受历史轮次 */
    public PrivateHistory.Turn history() {
        return new PrivateHistory.Turn(turnId,members.stream().map(input -> new PrivateHistory.Member(input.messageId(),
                input.eventId(),input.text(),input.occurredAt(),input.receivedAt(),input.sequence(),input.traceId())).toList(),assistantText);
    }
    /** 完整允许业务消息副本；在异步任务提交前移除凭证，并防止原调用方改变 JSON。 */
    public record Input(String messageId,String eventId,String text,JsonNode content,Instant occurredAt,
                        Instant receivedAt,long sequence,String traceId) {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=ModelObjectMapper.createNewObjectMapper();
        /**
         * 冻结原始允许业务字段，防止凭证或可变 JSON 进入异步队列。
         * @param messageId 原始消息标识
         * @param eventId 原始事件标识
         * @param text 完整原文
         * @param content 完整允许业务元数据
         * @param occurredAt 原事件时间
         * @param receivedAt 原接收时间
         * @param sequence 原接收序号
         * @param traceId 原追踪标识
         * @throws IllegalArgumentException 完整消息或追踪边界缺失时抛出
         */
        public Input {
            if(messageId==null || messageId.isBlank() || eventId==null || eventId.isBlank() || text==null || text.isBlank()
                    || content==null || occurredAt==null || receivedAt==null || sequence<1 || traceId==null || traceId.isBlank())
                throw new IllegalArgumentException("Invalid accepted input");
            content=MAPPER.valueToTree(LogData.business(content));
            if(!content.path("text").isTextual() || !text.equals(content.path("text").textValue()))
                throw new IllegalArgumentException("Input content mismatch");
        }
        /** @return 完整独立业务数据副本 */
        @Override public JsonNode content() { return content.deepCopy(); }
    }
}
