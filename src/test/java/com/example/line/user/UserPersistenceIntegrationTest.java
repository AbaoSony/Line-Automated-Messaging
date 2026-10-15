package com.example.line.user;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 验证重新关注、乱序状态保护、取消关注保留数据与可扩展的显式删除事务。 */
@SpringBootTest(properties = {"line.bot.channel-secret=fake-user-secret", "line.bot.channel-token=fake-user-token"})
@Import(UserPersistenceIntegrationTest.ExtraStorage.class)
class UserPersistenceIntegrationTest extends PostgresIntegrationSupport {
    private static final String USER = "Ucccccccccccccccccccccccccccccccc";
    @Autowired private UserService users;
    @Autowired private UserDataDeletionService deletion;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired private ExtraPurger extra;

    @BeforeEach
    void clearUserData() {
        drain();
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM line_user");
        extra.calls.set(0);
        extra.fail = false;
    }

    @Test
    void refollowUpdatesExistingUserAndUnfollowRetainsChat() {
        Instant initial = Instant.parse("2026-01-01T00:00:00Z");
        users.recordStatus(USER, true, initial);
        drain();
        jdbc.update("INSERT INTO chat_message (user_id, direction, message_type, content) VALUES (?, 'OUTBOUND', 'TEXT', '{}'::jsonb)", USER);
        users.recordStatus(USER, false, initial.plusSeconds(1));
        drain();
        assertThat(status()).isEqualTo("UNFOLLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE user_id = ?", Integer.class, USER)).isEqualTo(1);
        users.recordStatus(USER, true, initial.plusSeconds(2));
        drain();
        assertThat(status()).isEqualTo("FOLLOWING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM line_user WHERE user_id = ?", Integer.class, USER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unfollowed_at FROM line_user WHERE user_id = ?", java.sql.Timestamp.class, USER)).isNull();
    }

    @Test
    void lateOldLifecycleEventDoesNotOverrideNewStatus() {
        Instant recent = Instant.now();
        users.recordStatus(USER, true, recent);
        drain();
        users.recordStatus(USER, false, recent.minusSeconds(1));
        drain();
        assertThat(status()).isEqualTo("FOLLOWING");
    }

    @Test
    void deleteAllCallsEveryRegisteredPurgerAndRemovesUserAndChat() {
        seed();
        deletion.deleteAll(USER);
        assertThat(extra.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM line_user WHERE user_id = ?", Integer.class, USER)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE user_id = ?", Integer.class, USER)).isZero();
        deletion.deleteAll(USER);
        assertThat(extra.calls.get()).isEqualTo(2);
    }

    @Test
    void failedPurgerRollsBackBusinessDatabaseDeletion() {
        seed();
        extra.fail = true;
        assertThatThrownBy(() -> deletion.deleteAll(USER)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM line_user WHERE user_id = ?", Integer.class, USER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE user_id = ?", Integer.class, USER)).isEqualTo(1);
    }

    private void seed() {
        users.recordStatus(USER, true, Instant.now());
        drain();
        jdbc.update("INSERT INTO chat_message (user_id, direction, message_type, content) VALUES (?, 'OUTBOUND', 'TEXT', '{}'::jsonb)", USER);
    }

    private String status() { return jdbc.queryForObject("SELECT status FROM line_user WHERE user_id = ?", String.class, USER); }

    private void drain() {
        await().atMost(5, TimeUnit.SECONDS).until(() -> persistenceExecutor.getActiveCount() == 0
                && persistenceExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraStorage {
        @Bean ExtraPurger extraPurger() { return new ExtraPurger(); }
    }

    /** 模拟后续模块自行注册删除器，无需修改统一删除入口。 */
    static class ExtraPurger implements UserDataPurger {
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;

        @Override
        public void purge(String userId) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("Simulated storage failure");
        }
    }
}
