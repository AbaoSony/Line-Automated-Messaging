package com.example.line.ai.generation;

/** 描述纯文本生成结果和真实用量，未提供的用量保持 null，不伪造为零。 */
public record ChatResult(String text, String modelName, Integer inputTokens, Integer outputTokens,
                         long latencyMs, String status) {}
