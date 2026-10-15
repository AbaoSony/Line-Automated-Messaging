package com.example.line.chat;

import java.time.Instant;
import java.util.List;

/** 返回指定快照前的已配对私聊轮次与上一条私聊入站时间，读取失败通过异常表达。 */
public record PrivateHistory(List<Turn> turns, Instant lastInboundAt) {
    /** 私聊入站时间及排序边界，避免事件时间乱序时选择错误的上一条。 */
    public record InputTime(Instant occurredAt,Instant receivedAt,long sequence) {}
    /** 一轮只包含一条私聊文本及其已获 LINE 接受的正常 AI 回复。 */
    public record Turn(String messageId, String correlationId, String userText, String assistantText,
                       Instant occurredAt, Instant receivedAt, long sequence) {}
}
