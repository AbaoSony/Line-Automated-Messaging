package com.example.line.common.exception;

import com.example.line.messaging.LineSendException;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 为同步 HTTP 调用提供统一且不含敏感原文的错误响应，与 InternalPushController、
 * LineSendException 协作区分本地参数错误和 LINE 上游错误。
 * Webhook 工作线程中的失败由 WebhookService 记录，不会改变已经返回的接收确认。
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /**
     * 对外错误摘要，不携带 SDK 原始消息、请求正文或凭证。
     *
     * @param code 本服务稳定的错误分类，不是 LINE 原始错误正文
     * @param upstreamStatus 已知的 LINE HTTP 状态；本地校验或无 HTTP 响应时为 null
     */
    public record ApiError(String code, Integer upstreamStatus) {}
    /**
     * 将参数校验和 JSON 绑定错误转换为不暴露输入内容的 400 响应。
     *
     * @param exception 参数非法或请求体无法解析产生的异常，仅用于选择处理器，不输出其消息
     * @return HTTP 400，错误分类为 INVALID_REQUEST
     */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        BusinessLog.failure(log, Level.WARN, LogEvents.HTTP_REQUEST_FAILED, "Invalid HTTP request", exception,
                BusinessLog.fields("http_status", 400, "outcome", "FAILED"));
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", null));
    }
    /**
     * 将发送异常映射为网关错误或暂时不可用，避免把 LINE 鉴权错误与内部接口鉴权混淆。
     *
     * @param exception 发送层生成的不含敏感原因正文的异常
     * @return 瞬时错误或中断返回 503，其余上游错误返回 502，保留已知上游状态
     */
    @ExceptionHandler(LineSendException.class)
    public ResponseEntity<ApiError> lineError(LineSendException exception) {
        int status = exception.getReason() == LineSendException.Reason.TRANSIENT_FAILURE
                || exception.getReason() == LineSendException.Reason.INTERRUPTED ? 503 : 502;
        return ResponseEntity.status(status)
                .body(new ApiError("LINE_" + exception.getReason(), exception.getUpstreamStatus()));
    }
}
