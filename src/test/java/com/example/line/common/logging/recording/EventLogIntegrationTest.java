package com.example.line.common.logging.recording;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.common.persistence.PersistenceProperties;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 用同版本真实测试库验证迁移、独立池、幂等批写、字段边界和事件保留策略。 */
@SpringBootTest(properties = {"line.bot.channel-secret=fake-event-secret", "line.bot.channel-token=fake-event-token"})
class EventLogIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private EventLogRepository repository;
    @Autowired private EventRecorder recorder;
    @Autowired private EventRetentionTask retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meters;
    @Autowired @Qualifier("dataSource") private DataSource businessPool;
    @Autowired @Qualifier("logDataSource") private DataSource logPool;

    @Test
    void registeredEventsPreserveRawBusinessDataWithoutPersistingSecretsOrFrames() throws Exception {
        String marker = "fictional-event-" + UUID.randomUUID();
        String trace;
        try (var scope = LogContext.event(marker, "Ufictional-complete-user")) {
            trace = org.slf4j.MDC.get("trace_id");
            BusinessLog.write(LoggerFactory.getLogger(getClass()), Level.INFO, LogEvents.USER_MESSAGE_RECEIVED,
                    "Event persistence verification", BusinessLog.fields("content", Map.of("text", "完整虚构原文", "quoteToken", "fake-private-quote"),
                            "unexpected", "fake-private-extra", "stack_trace", List.of("fake-private-frame")));
            BusinessLog.write(LoggerFactory.getLogger(getClass()), Level.ERROR, LogEvents.PERSIST_FAILED,
                    "Failure must remain on stdout", Map.of());
            LoggerFactory.getLogger(getClass()).info("Unregistered framework style entry");
        }
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
        var rows = jdbc.queryForList("SELECT * FROM event_log WHERE trace_id = ?", trace);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("user_id")).isEqualTo("Ufictional-complete-user");
        String detail = rows.get(0).get("detail").toString();
        assertThat(detail).contains("完整虚构原文").doesNotContain("fake-private", "stack_trace", "unexpected", "quoteToken");
        assertThat(businessPool).isNotSameAs(logPool);
        assertThat(((HikariDataSource) businessPool).getPoolName()).isEqualTo("line-business");
        assertThat(((HikariDataSource) logPool).getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void repeatedBatchIdIsIgnoredAndCleanupIsDisabledUntilExplicitlyEnabled() {
        UUID id = UUID.randomUUID();
        var data = RecordData.snapshot(Map.of("ts", Instant.now().minus(Duration.ofDays(60)).toString(),
                "event", LogEvents.USER_FOLLOW, "level", "INFO", "trace_id", id.toString(),
                "service", "test", "env", "test", "version", "test", "instance", "test"));
        var entry = new RecordedEntry(id, false, data);
        repository.write(List.of(entry));
        repository.write(List.of(entry));
        assertThat(count(id)).isEqualTo(1);
        retention.clean();
        assertThat(count(id)).isEqualTo(1);
        var enabled = new EventRetentionTask(new EventRetentionProperties(true, 30),
                new PersistenceProperties(1, 1, Duration.ofSeconds(1), 1), repository, meters);
        enabled.clean();
        assertThat(count(id)).isZero();
        assertThatThrownBy(() -> new EventRetentionProperties(false, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private int count(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM event_log WHERE id = ?", Integer.class, id); }
}
