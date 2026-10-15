package com.example.line.ai.generation;

/** 为会话编排隔离外部模型厂商与 HTTP/SDK 类型，测试无需真实 API。 */
@FunctionalInterface
public interface ChatModelClient {
    /**
     * 在请求剩余预算内生成文本，不持有事件凭证。
     * @param request 人设、角色化历史和当前输入
     * @return 原始生成文本、状态与真实模型用量
     * @throws ModelFailure 模型、网络或统一截止时间失败时抛出安全分类异常
     */
    ChatResult generate(ChatRequest request);
}
