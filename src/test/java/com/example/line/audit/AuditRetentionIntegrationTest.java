package com.example.line.audit;

import com.example.line.common.logging.EventRecorder;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.logging.recording.EventLogRepository;
import com.example.line.common.persistence.PersistenceProperties;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 验证审计完整业务数据及单独的默认关闭、过期与批次限制，不能跟随事件清理自动删除。 */
@SpringBootTest(properties = {"line.bot.channel-secret=fake-audit-secret", "line.bot.channel-token=fake-audit-token"})
class AuditRetentionIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private AuditService audit;
    @Autowired private AuditRetentionTask retention;
    @Autowired private EventRecorder recorder;
    @Autowired private EventLogRepository repository;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void independentAuditCleanupDefaultsOffAndDeletesOnlyOneExpiredRowPerBatch() {
        String user = "Ufictional-retention-" + UUID.randomUUID();
        for (int index = 0; index < 3; index++) audit.record(LogEvents.INTERNAL_PUSH_INVOKED, "fictional-operator",
                "127.0.0.1", user, "SUCCESS", Map.of("content", Map.of("text", "虚构完整审计消息", "replyToken", "fake-private-reply")));
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
        assertThat(count(user)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT detail::text FROM audit_log WHERE target_user_id = ?", String.class, user).toString())
                .contains("虚构完整审计消息").doesNotContain("fake-private-reply", "replyToken");
        jdbc.update("UPDATE audit_log SET created_at = now() - interval '200 days' WHERE id IN "
                + "(SELECT id FROM audit_log WHERE target_user_id = ? LIMIT 2)", user);
        retention.clean();
        assertThat(count(user)).isEqualTo(3);
        var enabled = new AuditRetentionTask(new AuditProperties(true, 180),
                new PersistenceProperties(1, 1, Duration.ofSeconds(1), 1), repository, meters);
        enabled.clean();
        assertThat(count(user)).isEqualTo(2);
        enabled.clean();
        assertThat(count(user)).isEqualTo(1);
        assertThatThrownBy(() -> new AuditProperties(false, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private int count(String user) { return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_user_id = ?", Integer.class, user); }
}
