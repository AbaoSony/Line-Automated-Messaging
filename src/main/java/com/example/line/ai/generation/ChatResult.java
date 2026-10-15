package com.example.line.ai.generation;

/** 描述纯文本生成结果和真实用量，未提供的用量保持 null，不伪造为零。 */
public record ChatResult(String text, String modelName, Integer inputTokens, Integer outputTokens,
                         long latencyMs, String status, String incompleteReason,Integer reasoningTokens) {
    /**
     * 兼容没有未完成原因或推理明细的模型实现，未知数据保持为空。
     * @param text 完整生成正文
     * @param modelName 实际模型
     * @param inputTokens 实际输入用量
     * @param outputTokens 实际总输出用量，包含推理
     * @param latencyMs 生成耗时
     * @param status 完成状态
     */
    public ChatResult(String text,String modelName,Integer inputTokens,Integer outputTokens,long latencyMs,String status) {
        this(text,modelName,inputTokens,outputTokens,latencyMs,status,null,null);
    }
}
