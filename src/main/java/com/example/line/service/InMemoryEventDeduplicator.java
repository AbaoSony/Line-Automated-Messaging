package com.example.line.service;

import com.example.line.config.WebhookProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A null expiry denotes in-flight work; only completed entries expire.
 * 为 WebhookService 提供单进程的原子去重，占用与成功记录共享有界内存。
 * 与 WebhookProperties 协作设置 TTL 和容量；重启会清空，多个实例不共享状态。
 */
@Component
public class InMemoryEventDeduplicator implements EventDeduplicator {
    private final Map<String, Instant> entries = new HashMap<>();
    private final Duration ttl;
    private final int capacity;
    private final Clock clock;

    /**
     * 使用生产资源配置和 UTC 时钟构造去重缓存。
     *
     * @param properties 有效的缓存配置，默认成功保留 24 小时、最多 10000 个条目
     */
    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryEventDeduplicator(WebhookProperties properties) {
        this(properties.dedupTtl(), properties.dedupMaxEntries(), Clock.systemUTC());
    }

    // 可替换时钟便于精确验证过期边界；生产不依赖等待真实时间来测试 TTL。
    InMemoryEventDeduplicator(Duration ttl, int capacity, Clock clock) {
        this.ttl = ttl;
        this.capacity = capacity;
        this.clock = clock;
    }

    /**
     * 在同一同步区内清理已完成的过期项、检查占用并取得资格。
     *
     * @param eventId 接入层提供的非空白 webhookEventId；实现本身不做字段格式校验
     * @return true 表示本次占用成功；false 表示处理中或仍在成功保留期
     * @throws IllegalStateException 缓存已满且没有可清理的完成条目时抛出
     */
    @Override
    public synchronized boolean tryAcquire(String eventId) {
        Instant now = clock.instant();
        // null 表示仍有任务持有占用，不按 TTL 删除，避免慢任务尚未完成就被并发重投递执行。
        entries.entrySet().removeIf(entry -> entry.getValue() != null && !entry.getValue().isAfter(now));
        if (entries.containsKey(eventId)) {
            return false;
        }
        // 不为新事件驱逐有效记录，否则缓存饱和会转化为重复业务执行。
        if (entries.size() >= capacity) {
            throw new IllegalStateException("LINE deduplication cache is full");
        }
        entries.put(eventId, null);
        return true;
    }

    /**
     * 业务成功后开始 TTL 计时，保证保留期不被处理耗时提前消耗。
     *
     * @param eventId 当前调用已取得占用的事件标识；由 WebhookService 保证调用顺序
     */
    @Override
    public synchronized void complete(String eventId) {
        entries.put(eventId, clock.instant().plus(ttl));
    }

    /**
     * 业务失败时删除当前占用，允许未来投递重试，而非把失败永久当作成功。
     *
     * @param eventId 当前失败调用占用的事件标识；实现不校验持有者，调用方必须保证所有权
     */
    @Override
    public synchronized void release(String eventId) {
        entries.remove(eventId);
    }
}
