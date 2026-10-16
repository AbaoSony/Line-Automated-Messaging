package com.example.line.chat;

import com.example.line.common.persistence.AsyncPersistence;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.persistence.PersistenceProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 根据独立开关清理聊天，每次事务各处理一批过期消息和残缺轮次，防止长事务和占满落库队列。 */
@Component
class ChatRetentionTask {
    private final ChatRepository repository;
    private final ChatProperties properties;
    private final PersistenceProperties persistenceProperties;
    private final AsyncPersistence persistence;

    ChatRetentionTask(ChatRepository repository, ChatProperties properties,
                      PersistenceProperties persistenceProperties, AsyncPersistence persistence) {
        this.repository = repository;
        this.properties = properties;
        this.persistenceProperties = persistenceProperties;
        this.persistence = persistence;
    }

    @Scheduled(fixedDelayString = "${line.chat.cleanup-interval:1h}",
            initialDelayString = "${line.chat.cleanup-interval:1h}")
    void cleanExpired() {
        if (!properties.cleanupEnabled()) return;
        Instant cutoff = Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS);
        persistence.submit(LogEvents.CHAT_RETENTION_CLEANED, null,
                () -> repository.deleteExpiredBatch(cutoff, persistenceProperties.cleanupBatchSize()));
    }
}
