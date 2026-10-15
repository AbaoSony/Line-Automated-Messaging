package com.example.line.chat;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.common.persistence.PersistenceProperties;
import com.example.line.common.persistence.AsyncPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 验证真实 PostgreSQL 迁移、入站幂等、完整内容、历史排序与开关控制的批量保留策略。 */
@SpringBootTest(properties = {"line.bot.channel-secret=fake-chat-secret", "line.bot.channel-token=fake-chat-token"})
class ChatPersistenceIntegrationTest extends PostgresIntegrationSupport {
    private static final String USER = "Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Autowired private ChatMessageService messages;
    @Autowired private ChatHistoryService history;
    @Autowired private ChatRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired private AsyncPersistence persistence;

    @BeforeEach
    void clearMessages() {
        drain();
        jdbc.update("DELETE FROM chat_message");
    }

    @Test
    void concurrentDuplicateInboundWritesProduceOneRowAndKeepOriginalUserAndText() throws Exception {
        var content = mapper.readTree("""
                {"type":"text","text":"虚构完整中文消息","quoteToken":"fake-quote-credential",
                 "markAsReadToken":"fake-read-credential","metadata":{"api_key":"fake-api-credential","name":"完整元数据"}}
                """);
        for (int i = 0; i < 20; i++) {
            messages.recordInbound(USER, "fictional-message-1", "TEXT", content, Instant.now());
        }
        drain();
        var rows = history.findRecent(USER, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).userId()).isEqualTo(USER);
        assertThat(rows.get(0).content().path("text").asText()).isEqualTo("虚构完整中文消息");
        assertThat(rows.get(0).content().path("metadata").path("name").asText()).isEqualTo("完整元数据");
        assertThat(rows.get(0).content().toString()).doesNotContain("fake-quote-credential", "fake-read-credential", "fake-api-credential");
        assertThat(content.has("quoteToken")).isTrue();
    }

    @Test
    void outboundWritesAreNotDeduplicatedAndModelFieldsRemainNull() {
        var text = mapper.createObjectNode().put("text", "虚构回复原文");
        messages.recordOutbound(USER, "TEXT", text);
        messages.recordOutbound(USER, "TEXT", text);
        drain();
        var rows = history.findRecent(USER, 10);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.direction()).isEqualTo("OUTBOUND");
            assertThat(row.content().path("text").asText()).isEqualTo("虚构回复原文");
            assertThat(row.modelName()).isNull();
            assertThat(row.inputTokens()).isNull();
            assertThat(row.outputTokens()).isNull();
            assertThat(row.latencyMs()).isNull();
        });
    }

    @Test
    void historyReturnsLatestLimitInChronologicalOrderWithStableTimestampTies() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        repository.insert(USER, "INBOUND", "first", "TEXT", "{}", base);
        repository.insert(USER, "INBOUND", "second", "TEXT", "{}", base.plusSeconds(1));
        repository.insert(USER, "INBOUND", "third", "TEXT", "{}", base.plusSeconds(1));
        repository.insert("Ubbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "INBOUND", "other-user", "TEXT", "{}", base.plusSeconds(2));
        assertThat(history.findRecent(USER, 2)).extracting(ChatMessage::lineMessageId).containsExactly("second", "third");
        assertThat(history.findRecent("missing-fictional-user", 2)).isEmpty();
        for (int limit : List.of(0, -1, 101)) {
            assertThatThrownBy(() -> history.findRecent(USER, limit)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> history.findRecent(" ", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanupDisabledPreservesExpiredRowsAndEnabledCleanupDeletesAtMostOneBatch() {
        Instant old = Instant.now().minus(Duration.ofDays(100));
        for (int i = 0; i < 3; i++) repository.insert(USER, "INBOUND", "old-" + i, "TEXT", "{}", old);
        repository.insert(USER, "INBOUND", "new", "TEXT", "{}", Instant.now());
        var resources = new PersistenceProperties(1, 10, Duration.ofSeconds(1), 2);
        new ChatRetentionTask(repository, new ChatProperties(false, 90, 100), resources, persistence).cleanExpired();
        drain();
        assertThat(history.findRecent(USER, 10)).hasSize(4);
        var enabled = new ChatRetentionTask(repository, new ChatProperties(true, 90, 100), resources, persistence);
        enabled.cleanExpired();
        drain();
        assertThat(history.findRecent(USER, 10)).hasSize(2);
        enabled.cleanExpired();
        drain();
        assertThat(history.findRecent(USER, 10)).extracting(ChatMessage::lineMessageId).containsExactly("new");
    }

    @Test
    void cleanupKeepsRowsExactlyAtCutoff() {
        Instant cutoff = Instant.parse("2026-04-01T00:00:00Z");
        repository.insert(USER, "INBOUND", "before-cutoff", "TEXT", "{}", cutoff.minusSeconds(1));
        repository.insert(USER, "INBOUND", "at-cutoff", "TEXT", "{}", cutoff);
        assertThat(repository.deleteExpiredBatch(cutoff, 100)).isEqualTo(1);
        assertThat(history.findRecent(USER, 10)).extracting(ChatMessage::lineMessageId).containsExactly("at-cutoff");
    }

    private void drain() {
        await().atMost(5, TimeUnit.SECONDS).until(() -> persistenceExecutor.getActiveCount() == 0
                && persistenceExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }
}
