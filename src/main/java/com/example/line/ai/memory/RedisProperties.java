package com.example.line.ai.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 读取 Redis 连接信息，禁止生成包含密码的字符串表示。 */
@ConfigurationProperties("line.ai.redis")
final class RedisProperties {
    private final String host, username, password;
    private final int port;
    private final boolean ssl;
    RedisProperties(String host, int port, String username, String password, boolean ssl) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Set REDIS_HOST and valid REDIS_PORT");
        this.host=host; this.port=port; this.username=username; this.password=password; this.ssl=ssl;
    }
    String host() { return host; } int port() { return port; }
    String username() { return username; } String password() { return password; } boolean ssl() { return ssl; }
}
