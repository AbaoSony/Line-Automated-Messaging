package com.example.line.ai.decision;

/** 时机模型厂商无关入口，无隐式重试；策略输出没有发送或调度权限。 */
public interface TimingModelClient {
    /**
     * 执行至多一次物理策略请求。
     * @param request 固定版本业务快照与原截止
     * @return 完整响应及经过严格校验的可选建议
     * @throws TimingFailure 请求未发起、取消、超时或上游失败时抛出安全分类
     */
    TimingResult decide(TimingRequest request);
}
