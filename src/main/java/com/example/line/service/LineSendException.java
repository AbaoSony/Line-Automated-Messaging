package com.example.line.service;

/**
 * Sanitized failure; never retain sensitive SDK exception messages or bodies.
 * 作为 LineApiCaller 到同步异常处理器、异步接入服务之间的安全错误边界，
 * 仅保留分类和已知 HTTP 状态，不保存可能含凭证或正文的 SDK 原始原因。
 */
public class LineSendException extends RuntimeException {
    /**
     * 发送失败分类：客户端参数/鉴权错误、瞬时故障、其他 SDK 故障与线程中断，
     * 供 ApiExceptionHandler 映射状态，避免调用方直接解释上游敏感错误正文。
     */
    public enum Reason { CLIENT_ERROR, TRANSIENT_FAILURE, UNEXPECTED_FAILURE, INTERRUPTED }
    private final Reason reason;
    private final Integer upstreamStatus;
    /**
     * 生成可安全跨层传递的发送异常，不连接原始 SDK 异常链。
     *
     * @param reason 调用层提供的失败分类，应为非 null 的已定义枚举值
     * @param upstreamStatus 已知的 LINE HTTP 状态；网络、超时或无上游响应时为 null
     */
    public LineSendException(Reason reason, Integer upstreamStatus) {
        super("LINE message request failed: " + reason);
        this.reason = reason;
        this.upstreamStatus = upstreamStatus;
    }
    public Reason getReason() { return reason; }
    public Integer getUpstreamStatus() { return upstreamStatus; }
}
