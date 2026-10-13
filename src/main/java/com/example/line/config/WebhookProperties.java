package com.example.line.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("line.webhook")
public record WebhookProperties(int workers, int queueCapacity, Duration dedupTtl,
                                int dedupMaxEntries, long contentMaxBytes) {
    public WebhookProperties {
        if (workers < 1 || queueCapacity < 1 || dedupMaxEntries < 1 || contentMaxBytes < 1
                || dedupTtl == null || dedupTtl.isNegative() || dedupTtl.isZero()) {
            throw new IllegalArgumentException("line.webhook requires positive workers, queue-capacity, "
                    + "dedup-ttl, dedup-max-entries and content-max-bytes");
        }
    }
}
