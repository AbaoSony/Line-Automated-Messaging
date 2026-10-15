package com.example.line.common.logging;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.*;

/** 验证管理端口只绑定回环地址，仅开放 health/info/metrics，不在业务端口暴露管理接口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"line.bot.channel-secret=fake-management-secret", "line.bot.channel-token=fake-management-token"})
class ManagementIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private TestRestTemplate http;
    @Autowired private Environment environment;
    @org.springframework.beans.factory.annotation.Value("${local.management.port}") private int managementPort;

    @Test
    void onlyAllowlistedEndpointsAreAvailableOnLoopbackManagementServer() {
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("management.endpoints.jmx.exposure.exclude")).isEqualTo("*");
        assertThat(managementPort).isNotEqualTo(environment.getProperty("local.server.port", Integer.class));
        for (String endpoint : List.of("health", "info", "metrics")) {
            assertThat(http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/" + endpoint, String.class).getStatusCode().value())
                    .isEqualTo(200);
            assertThat(http.getForEntity("/actuator/" + endpoint, String.class).getStatusCode().value()).isEqualTo(404);
        }
        for (String endpoint : List.of("env", "beans", "loggers", "heapdump", "shutdown")) {
            assertThat(http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/" + endpoint, String.class).getStatusCode().value())
                    .isEqualTo(404);
        }
        String metrics = http.getForObject("http://127.0.0.1:" + managementPort + "/actuator/metrics", String.class);
        assertThat(metrics).contains("webhook.requests", "webhook.signature.failed", "webhook.dedup.hits", "webhook.queue.size",
                "webhook.rejected", "line.reply.failed", "line.push.failed", "persistence.queue.size", "log.dropped");
        assertThat(metrics).doesNotContain("fake-management-secret", "fake-management-token", "user_id", "trace_id");
    }
}
