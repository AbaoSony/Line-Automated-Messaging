package com.example.line.messaging.internal;

import com.example.line.messaging.LineMessageService;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogEvents;
import com.example.line.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 承载仅供调试的同步 Push 接口，先通过 InternalPushProperties 的开关和独立鉴权，
 * 再调用 LineMessageService；发送异常交由 ApiExceptionHandler 输出安全摘要。
 * 默认关闭，不应把调试鉴权值替换成 LINE Secret 或 Access Token。
 */
@RestController
public class InternalPushController {
    private final InternalPushProperties properties;
    private final LineMessageService messages;
    private final AuditService audit;
    /**
     * 注入调试访问策略与统一消息发送服务。
     *
     * @param properties 调试开关和独立鉴权配置，启用时鉴权值必须非空白
     * @param messages 执行参数校验、SDK Push 和有限幂等重试的服务
     * @param audit 通过独立队列记录敏感操作，不阻塞消息发送
     */
    public InternalPushController(InternalPushProperties properties, LineMessageService messages, AuditService audit) {
        this.properties = properties;
        this.messages = messages;
        this.audit = audit;
    }
    /** 内部文本推送请求，字段限制由消息发送服务校验，不代表已经通过鉴权。 */
    public record PushTextRequest(String userId, String text) {}
    /**
     * 返回 LINE 已接受请求的摘要，而非用户已经收到或读取的证明。
     *
     * @param status 接受状态，当前成功值为 ACCEPTED
     * @param retryKey 本次调用生成的 UUID，同一次自动重试复用，跨 HTTP 调用不复用
     * @param alreadyAccepted 是否收到带 accepted-request-id 的 409，表明同一请求此前已被接受
     */
    public record PushResponse(String status, String retryKey, boolean alreadyAccepted) {}
    /**
     * 鉴权通过后同步推送一条文本，并返回上游接受结果。
     * JSON 绑定发生在本方法前，非法 JSON 可能在开关检查和鉴权前被框架拒绝。
     *
     * @param token X-Internal-Token 请求头；启用接口时必须与独立调试配置匹配，缺失视为未授权
     * @param request 文本请求；userId 必须为 U 加 32 个小写十六进制字符，text 非空白且不超过 5000 个 UTF-16 单元
     * @param httpRequest 获取直接连接来源，不使用客户端可伪造的转发头
     * @return 关闭时为 404、鉴权失败为 401、LINE 接受时为带 PushResponse 的 200
     * @throws IllegalArgumentException 请求字段不满足发送层约束时抛出，由统一处理器转换为 400
     * @throws com.example.line.messaging.LineSendException LINE 调用失败或中断时抛出，由统一处理器转换为 502/503
     */
    @PostMapping("/internal/push")
    public ResponseEntity<?> push(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                 @RequestBody PushTextRequest request, HttpServletRequest httpRequest) {
        if (!properties.isEnabled()) return ResponseEntity.notFound().build();
        // 字节比较避免按匹配前缀逐字符提前返回；不得记录提供值或配置值来排查鉴权失败。
        if (token == null || !MessageDigest.isEqual(properties.getToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        try (var scope = LogContext.user(request.userId())) {
            try {
                var receipt = messages.pushText(request.userId(), request.text());
                audit.record(LogEvents.INTERNAL_PUSH_INVOKED, "internal-token", httpRequest.getRemoteAddr(), request.userId(), "SUCCESS",
                        BusinessLog.fields("outcome", "SUCCESS", "http_status", 200,
                                "content", BusinessLog.fields("text", request.text())));
                return ResponseEntity.ok(new PushResponse("ACCEPTED", receipt.retryKey().toString(), receipt.alreadyAccepted()));
            } catch (RuntimeException failure) {
                audit.record(LogEvents.INTERNAL_PUSH_INVOKED, "internal-token", httpRequest.getRemoteAddr(), request.userId(), "FAILED",
                        BusinessLog.fields("error_type", failure.getClass().getSimpleName(),
                                "content", BusinessLog.fields("text", request.text())));
                throw failure;
            }
        }
    }
}
