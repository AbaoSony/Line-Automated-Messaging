package com.example.line.common.web;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证真实启动的 HTTP 服务返回健康状态，而不调用 LINE API。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "line.bot.channel-secret=test-secret", "line.bot.channel-token=test-token"
})
class HealthIntegrationTest extends PostgresIntegrationSupport {
    @Autowired
    private TestRestTemplate restTemplate;

    // 场景：健康接口返回 HTTP 200 和约定的 UP 响应。
    @Test
    void healthReturnsUp() {
        var response = restTemplate.getForEntity("/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
}
