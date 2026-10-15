package com.example.line.common.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 保存业务数据库连接配置，供 DatabaseConfig 装配连接池；不生成包含凭证的字符串表示。 */
@ConfigurationProperties("line.database")
public final class DatabaseProperties {
    private final String url;
    private final String username;
    private final String password;
    private final int poolSize;
    private final long connectionTimeoutMs;

    /**
     * 校验连接信息与连接池边界，不在错误中输出配置值。
     * @param url 由 DATABASE_URL 注入的 JDBC 地址
     * @param username 由 DATABASE_USERNAME 注入的账号
     * @param password 由 DATABASE_PASSWORD 注入的密码
     * @param poolSize 最大连接数
     * @param connectionTimeoutMs 获取连接超时毫秒数
     * @throws IllegalArgumentException 必填项缺失或连接池配置无效时抛出
     */
    public DatabaseProperties(String url, String username, String password, int poolSize, long connectionTimeoutMs) {
        if (url == null || url.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Missing required database environment variables: "
                    + "DATABASE_URL, DATABASE_USERNAME, DATABASE_PASSWORD");
        }
        if (poolSize < 1 || connectionTimeoutMs < 250) {
            throw new IllegalArgumentException("Database pool-size must be positive and connection-timeout-ms >= 250");
        }
        this.url = url;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    String url() { return url; }
    String username() { return username; }
    String password() { return password; }
    int poolSize() { return poolSize; }
    long connectionTimeoutMs() { return connectionTimeoutMs; }
}
