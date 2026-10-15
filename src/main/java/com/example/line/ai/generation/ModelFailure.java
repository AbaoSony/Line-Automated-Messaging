package com.example.line.ai.generation;

/** 只暴露固定错误分类，不传播可能包含 API Key 或原始请求的 SDK 异常消息。 */
public final class ModelFailure extends RuntimeException {
    private final String kind;
    /**
     * 创建可用于日志与兜底分类的安全异常。
     * @param kind 固定错误分类
     */
    public ModelFailure(String kind) { super(kind); this.kind = kind; }
    /** @return 固定错误分类 */
    public String kind() { return kind; }
}
