package com.example.line.ai.generation;

import com.example.line.common.execution.Deadline;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 仅传递允许的业务上下文与剩余预算，不含 Reply Token、API Key 或 OpenAI SDK 类型。 */
public record ChatRequest(String systemPrompt, List<HistoryMessage> history, String userMessage,
                          String messageId, String correlationId, Instant occurredAt, Deadline deadline,
                          List<String> currentMessages, BooleanSupplier reserveRetry) {
    /**
     * 保留 P1 单成员入口的兼容构造；共享额度由 P2 入口显式提供。
     * @param systemPrompt 原始人设
     * @param history 已接受的完整历史
     * @param userMessage 当前原文
     * @param messageId 业务消息标识
     * @param correlationId 关联标识
     * @param occurredAt 事件时间
     * @param deadline 原始阶段截止
     */
    public ChatRequest(String systemPrompt, List<HistoryMessage> history, String userMessage,
                       String messageId, String correlationId, Instant occurredAt, Deadline deadline) {
        this(systemPrompt, history, userMessage, messageId, correlationId, occurredAt, deadline, List.of(userMessage), () -> true);
    }
    /**
     * 防止模型输入在版本冻结后被调用方修改，回调只供适配器申请共享第二次请求额度。
     * @param systemPrompt 完整人设
     * @param history 已接受完整旧轮的角色列表
     * @param userMessage 兼容单条读取的首成员原文
     * @param messageId 首成员消息标识
     * @param correlationId 首成员事件标识
     * @param occurredAt 首成员事件时间
     * @param deadline 共享首次正文截止
     * @param currentMessages 全部有序当前成员原文
     * @param reserveRetry 当前版本预占第二次物理请求的程序回调
     * @throws NullPointerException 历史或当前成员列表缺失时抛出
     */
    public ChatRequest { history = List.copyOf(history); currentMessages = List.copyOf(currentMessages); }
    /** 历史消息保留原有 user/assistant 角色，用户原文不提升为系统指令。 */
    public record HistoryMessage(String role, String content) {}
}
