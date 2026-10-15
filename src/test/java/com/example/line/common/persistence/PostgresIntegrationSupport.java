package com.example.line.common.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 统一启动测试专用 PostgreSQL，全部连接信息由容器生成；禁止回退真实数据库或跳过迁移。 */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresIntegrationSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    protected com.example.line.ai.generation.ChatModelClient testModel;
    @org.junit.jupiter.api.BeforeEach
    void prepareFictionalModel() {
        org.mockito.Mockito.doAnswer(call -> {
            var request=call.getArgument(0,com.example.line.ai.generation.ChatRequest.class);
            return new com.example.line.ai.generation.ChatResult(request.userMessage(),"gpt-6.1-sol",10,10,1,"COMPLETED");
        }).when(testModel).generate(org.mockito.ArgumentMatchers.any());
    }
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getProperty("test.postgresql.image")));

    static { POSTGRES.start(); }
    private static final org.testcontainers.containers.GenericContainer<?> REDIS=new org.testcontainers.containers.GenericContainer<>(
            DockerImageName.parse(System.getProperty("test.redis.image"))).withExposedPorts(6379);
    static {REDIS.start();}

    /**
     * 使用当前测试容器覆盖业务数据源，保留生产配置快速失败行为。
     * @param registry Spring 测试动态配置表
     */
    @DynamicPropertySource
    protected static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("line.database.url", POSTGRES::getJdbcUrl);
        registry.add("line.database.username", POSTGRES::getUsername);
        registry.add("line.database.password", POSTGRES::getPassword);
        registry.add("management.server.port", () -> "0");
        registry.add("line.ai.redis.host",REDIS::getHost);
        registry.add("line.ai.redis.port",() -> REDIS.getMappedPort(6379));
        registry.add("line.ai.memory.namespace",() -> "fictional-tests");
        registry.add("line.ai.credentials.api-key",() -> "fake-openai-api-key");
    }
}
