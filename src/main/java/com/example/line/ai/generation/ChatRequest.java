package com.example.line.ai.generation;

import com.example.line.common.execution.Deadline;
import java.time.Instant;
import java.util.List;

/** 仅传递允许的业务上下文与剩余预算，不含 Reply Token、API Key 或 OpenAI SDK 类型。 */
public record ChatRequest(String systemPrompt, List<HistoryMessage> history, String userMessage,
                          String messageId, String correlationId, Instant occurredAt, Deadline deadline) {
    /** 历史消息保留原有 user/assistant 角色，用户原文不提升为系统指令。 */
    public record HistoryMessage(String role, String content) {}
}
