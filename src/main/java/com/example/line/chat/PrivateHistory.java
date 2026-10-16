package com.example.line.chat;

import java.time.Instant;
import java.util.List;

/** 返回指定快照前的已配对私聊轮次与上一条私聊入站时间，读取失败通过异常表达。 */
public record PrivateHistory(List<Turn> turns, Instant lastInboundAt) {
    /** 私聊入站时间及排序边界，避免事件时间乱序时选择错误的上一条。 */
    public record InputTime(Instant occurredAt,Instant receivedAt,long sequence) {}
    /** 一轮包含全部有序私聊成员及其已获 LINE 接受的唯一正常 AI 回复。 */
    public record Turn(String messageId, String correlationId, String userText, String assistantText,
                       Instant occurredAt, Instant receivedAt, long sequence, List<Member> members, java.util.UUID turnId) {
        /**
         * 保留单成员调用入口，读取和裁剪仍以完整轮次为单位。
         * @param messageId 入站标识
         * @param correlationId 事件关联
         * @param userText 完整原文
         * @param assistantText 已接受正文
         * @param occurredAt 事件时间
         * @param receivedAt 接收时间
         * @param sequence 接收序号
         */
        public Turn(String messageId,String correlationId,String userText,String assistantText,
                    Instant occurredAt,Instant receivedAt,long sequence) {
            this(messageId,correlationId,userText,assistantText,occurredAt,receivedAt,sequence,
                    List.of(new Member(messageId,correlationId,userText,occurredAt,receivedAt,sequence,null)),null);
        }
        /**
         * 从完整成员建立轮次，兼容访问器始终指向首条，不能用来展开多成员上下文。
         * @param turnId 稳定轮次标识
         * @param members 全部有序成员
         * @param assistantText 已接受正文
         */
        public Turn(java.util.UUID turnId,List<Member> members,String assistantText) {
            this(members.get(0).messageId(),members.get(0).correlationId(),members.get(0).text(),assistantText,
                    members.get(0).occurredAt(),members.get(0).receivedAt(),members.get(0).sequence(),members,turnId);
        }
        /**
         * 固定成员集合，避免缓存快照在生成期间被调用方更改。
         * @param messageId 首条消息标识
         * @param correlationId 首条事件标识
         * @param userText 首条原文，展开历史时使用 members
         * @param assistantText 已接受正文
         * @param occurredAt 首条事件时间
         * @param receivedAt 首条接收时间
         * @param sequence 首条接收序号
         * @param members 完整有序成员
         * @param turnId 稳定轮次标识，旧单成员调用可为空
         * @throws IllegalArgumentException 轮次缺失成员时抛出
         */
        public Turn {
            members=List.copyOf(members);
            if(members.isEmpty())throw new IllegalArgumentException("Missing history members");
        }
    }
    /** 完整成员原文与原始排序/追踪信息；旧迁移 trace 可为空，不包含凭证或草稿。 */
    public record Member(String messageId,String correlationId,String text,Instant occurredAt,
                         Instant receivedAt,long sequence,String traceId) {}
}
