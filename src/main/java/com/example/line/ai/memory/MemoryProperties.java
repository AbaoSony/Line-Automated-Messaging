package com.example.line.ai.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 配置私聊缓存与回源上限，缓存 TTL 不能改变 PostgreSQL 的保留策略。 */
@ConfigurationProperties("line.ai.memory")
public record MemoryProperties(String namespace, @DefaultValue("10") int rounds,
                               @DefaultValue("7d") Duration ttl, @DefaultValue("200") int scanLimit,
                               @DefaultValue("12") int rateLimit, @DefaultValue("60s") Duration rateWindow) {
    /**
     * 校验 Channel 命名空间及有界资源，避免不同机器人复用缓存。
     * @throws IllegalArgumentException 命名空间或资源边界无效时抛出
     */
    public MemoryProperties {
        if (namespace == null || namespace.isBlank() || namespace.contains("{") || namespace.contains("}")
                || rounds < 1 || rounds > 100 || scanLimit < rounds*2 || scanLimit > 1000 || rateLimit < 1
                || ttl == null || ttl.isZero() || ttl.isNegative() || rateWindow == null || rateWindow.isZero() || rateWindow.isNegative())
            throw new IllegalArgumentException("Invalid AI memory settings; set AI_NAMESPACE");
    }
}
