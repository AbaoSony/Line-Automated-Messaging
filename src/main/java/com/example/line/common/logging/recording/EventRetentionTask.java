package com.example.line.common.logging.recording;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.persistence.PersistenceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在独立日志连接池按单批清理过期事件；默认关闭，失败不影响收发消息。 */
@Component
@EnableConfigurationProperties(EventRetentionProperties.class)
class EventRetentionTask {
    private final EventRetentionProperties properties;
    private final PersistenceProperties persistence;
    private final EventLogRepository repository;
    private final MeterRegistry meters;

    EventRetentionTask(EventRetentionProperties properties, PersistenceProperties persistence,
                       EventLogRepository repository, MeterRegistry meters) {
        this.properties = properties;
        this.persistence = persistence;
        this.repository = repository;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${line.event-log.cleanup-interval:1h}",
            initialDelayString = "${line.event-log.cleanup-interval:1h}")
    void clean() {
        if (!properties.cleanupEnabled()) return;
        try (var scope = LogContext.user(null)) {
            repository.cleanEvents(Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS), persistence.cleanupBatchSize());
        } catch (RuntimeException failure) {
            meters.counter("persist.failed", "operation", "event_log.cleanup").increment();
            BusinessLog.failure(LoggerFactory.getLogger(EventRetentionTask.class), Level.ERROR, LogEvents.PERSIST_FAILED,
                    "Event retention cleanup failed", failure, BusinessLog.fields("operation", "event_log.cleanup", "outcome", "FAILED"));
        }
    }
}
