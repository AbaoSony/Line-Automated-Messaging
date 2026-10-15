package com.example.line.audit;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

/** 在内部推送和显式删除边界记录操作者、原始目标用户、来源和结果；审计故障不影响主流程。 */
@Service
public class AuditService {
    private final AuditRepository repository;
    private final MeterRegistry meters;

    /**
     * 注入异步审计存储与失败指标。
     * @param repository 通过独立日志队列写入的存储入口
     * @param meters 失败指标注册表
     */
    AuditService(AuditRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

    /**
     * 同时写安全 stdout 和审计队列，不抛出持久化异常；失败只产生 persist.failed。
     * @param event 已登记敏感操作事件
     * @param operator 稳定操作者名称，不得传入鉴权凭证
     * @param sourceIp 连接来源地址；后台手动调用允许为空，不采信转发头
     * @param userId 原始完整目标用户标识
     * @param outcome SUCCESS / FAILED / SKIPPED
     * @param details 白名单业务字段，可保留完整消息原文
     */
    public void record(String event, String operator, String sourceIp, String userId,
                       String outcome, Map<String, ?> details) {
        try (var scope = LogContext.user(userId)) {
            var fields = new java.util.LinkedHashMap<String, Object>(details);
            fields.put("event", event);
            fields.put("operator", operator);
            fields.put("source_ip", sourceIp);
            fields.put("target_user_id", userId == null || userId.isBlank() ? "unknown" : userId);
            fields.put("user_id", userId);
            fields.put("outcome", outcome);
            fields.put("trace_id", MDC.get("trace_id"));
            fields.put("webhook_event_id", MDC.get("webhook_event_id"));
            fields.put("ts", Instant.now().toString());
            BusinessLog.write(LoggerFactory.getLogger(AuditService.class),
                    outcome.equals("FAILED") ? Level.ERROR : Level.INFO, event, "Sensitive operation audited", fields);
            repository.record(fields);
        } catch (RuntimeException failure) {
            meters.counter("persist.failed", "operation", "audit_log").increment();
            try (var scope = LogContext.user(userId)) {
                BusinessLog.failure(LoggerFactory.getLogger(AuditService.class), Level.ERROR, LogEvents.PERSIST_FAILED,
                        "Audit recording failed", failure, BusinessLog.fields("operation", "audit_log", "outcome", "FAILED"));
            }
        }
    }
}
