package com.example.line.audit;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.logging.recording.EventLogRepository;
import com.example.line.common.persistence.PersistenceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 显式启用时在独立日志连接池分批清理过期审计，失败只报告 persist.failed。 */
@Component
@EnableConfigurationProperties(AuditProperties.class)
class AuditRetentionTask {
    private final AuditProperties properties;
    private final PersistenceProperties persistence;
    private final EventLogRepository repository;
    private final MeterRegistry meters;

    AuditRetentionTask(AuditProperties properties, PersistenceProperties persistence,
                       EventLogRepository repository, MeterRegistry meters) {
        this.properties = properties;
        this.persistence = persistence;
        this.repository = repository;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${line.audit-log.cleanup-interval:1h}",
            initialDelayString = "${line.audit-log.cleanup-interval:1h}")
    void clean() {
        if (!properties.cleanupEnabled()) return;
        try (var scope = LogContext.user(null)) {
            repository.cleanAudits(Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS), persistence.cleanupBatchSize());
        } catch (RuntimeException failure) {
            meters.counter("persist.failed", "operation", "audit_log.cleanup").increment();
            BusinessLog.failure(LoggerFactory.getLogger(AuditRetentionTask.class), Level.ERROR, LogEvents.PERSIST_FAILED,
                    "Audit retention cleanup failed", failure, BusinessLog.fields("operation", "audit_log.cleanup", "outcome", "FAILED"));
        }
    }
}
