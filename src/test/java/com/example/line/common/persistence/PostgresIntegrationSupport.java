package com.example.line.common.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 统一启动测试专用 PostgreSQL，全部连接信息由容器生成；禁止回退真实数据库或跳过迁移。 */
public abstract class PostgresIntegrationSupport {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getProperty("test.postgresql.image")));

    static { POSTGRES.start(); }

    /**
     * 使用当前测试容器覆盖业务数据源，保留生产配置快速失败行为。
     * @param registry Spring 测试动态配置表
     */
    @DynamicPropertySource
    protected static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("line.database.url", POSTGRES::getJdbcUrl);
        registry.add("line.database.username", POSTGRES::getUsername);
        registry.add("line.database.password", POSTGRES::getPassword);
    }
}
