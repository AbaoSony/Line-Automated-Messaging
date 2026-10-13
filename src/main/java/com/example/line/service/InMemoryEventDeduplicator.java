package com.example.line.service;

import com.example.line.config.WebhookProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A null expiry denotes in-flight work; only completed entries expire. */
@Component
public class InMemoryEventDeduplicator implements EventDeduplicator {
    private final Map<String, Instant> entries = new HashMap<>();
    private final Duration ttl;
    private final int capacity;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryEventDeduplicator(WebhookProperties properties) {
        this(properties.dedupTtl(), properties.dedupMaxEntries(), Clock.systemUTC());
    }

    InMemoryEventDeduplicator(Duration ttl, int capacity, Clock clock) {
        this.ttl = ttl;
        this.capacity = capacity;
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquire(String eventId) {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> entry.getValue() != null && !entry.getValue().isAfter(now));
        if (entries.containsKey(eventId)) {
            return false;
        }
        if (entries.size() >= capacity) {
            throw new IllegalStateException("LINE deduplication cache is full");
        }
        entries.put(eventId, null);
        return true;
    }

    @Override
    public synchronized void complete(String eventId) {
        entries.put(eventId, clock.instant().plus(ttl));
    }

    @Override
    public synchronized void release(String eventId) {
        entries.remove(eventId);
    }
}
