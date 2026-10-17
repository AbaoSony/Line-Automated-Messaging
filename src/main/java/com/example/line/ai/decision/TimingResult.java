package com.example.line.ai.decision;

/** 保留时机响应的完整允许正文与实际用量；拒绝/非法响应无建议，未知用量为空。 */
public record TimingResult(TimingDecision decision,String text,String model,Integer inputTokens,Integer outputTokens,
                           Integer reasoningTokens,long latencyMillis,String status,String errorKind) {}
