package com.example.line.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

/** 验证固定 JSON 字段、完整业务数据、换行转义、凭证白名单与第三方异常的安全调用帧。 */
class JsonLogFormatterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsFullBusinessDataAndAlwaysIncludesRequiredIdentityFields() throws Exception {
        var event = event("com.example.line.test");
        event.setMDCPropertyMap(Map.of("trace_id", "fictional-trace", "user_id", "Ufictional-complete-user", "Authorization", "fake-auth-token"));
        String text = "虚构完整文本\n下一行" + "未截断".repeat(2000);
        event.addKeyValuePair(new KeyValuePair("event", LogEvents.USER_MESSAGE_RECEIVED));
        event.addKeyValuePair(new KeyValuePair("content", Map.of("text", text, "quoteToken", "fake-quote-token",
                "metadata", Map.of("api_key", "fake-api-key", "fileName", "虚构文件.pdf"))));
        event.addKeyValuePair(new KeyValuePair("replyToken", "fake-reply-token"));
        var env = new MockEnvironment().withProperty("line.logging.service", "fictional-service")
                .withProperty("line.logging.env", "fictional-env").withProperty("line.logging.version", "fictional-version")
                .withProperty("line.logging.instance", "fictional-instance");
        String json = new JsonLogFormatter(env).format(event);
        var actual = mapper.readTree(json);
        assertThat(json.lines().count()).isEqualTo(1);
        for (String field : new String[]{"ts", "level", "service", "env", "version", "instance", "trace_id", "event"}) {
            assertThat(actual.path(field).asText()).isNotBlank();
        }
        assertThat(actual.path("service").asText()).isEqualTo("fictional-service");
        assertThat(actual.path("env").asText()).isEqualTo("fictional-env");
        assertThat(actual.path("version").asText()).isEqualTo("fictional-version");
        assertThat(actual.path("instance").asText()).isEqualTo("fictional-instance");
        assertThat(actual.path("user_id").asText()).isEqualTo("Ufictional-complete-user");
        assertThat(actual.path("content").path("text").asText()).isEqualTo(text);
        assertThat(actual.path("content").path("metadata").path("fileName").asText()).isEqualTo("虚构文件.pdf");
        assertThat(json).doesNotContain("fake-auth-token", "fake-quote-token", "fake-reply-token", "fake-api-key");
    }

    @Test
    void startupAndFrameworkFailuresHaveTraceAndSafeFramesWithoutCredentialMessages() throws Exception {
        var event = event("org.springframework.jdbc.core.JdbcTemplate", "fake-database-address fake-database-password");
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("fake-exception-secret")));
        String json = new JsonLogFormatter(new MockEnvironment()).format(event);
        var actual = mapper.readTree(json);
        assertThat(UUID.fromString(actual.path("trace_id").asText())).isNotNull();
        assertThat(actual.path("event").asText()).isEqualTo(LogEvents.APP_LIFECYCLE);
        assertThat(actual.path("stack_trace").size()).isPositive();
        assertThat(json).contains("IllegalStateException").doesNotContain("fake-database-address", "fake-database-password", "fake-exception-secret");
    }

    @Test
    void unknownEventsAreRejectedAndMissingUserIsExplicitlyNull() throws Exception {
        var event = event("com.example.line.test");
        event.addKeyValuePair(new KeyValuePair("event", "unregistered-fake-event"));
        var actual = mapper.readTree(new JsonLogFormatter(new MockEnvironment()).format(event));
        assertThat(actual.path("event").asText()).isEqualTo(LogEvents.APP_LIFECYCLE);
        assertThat(actual.get("user_id").isNull()).isTrue();
        assertThatThrownBy(() -> BusinessLog.write(LoggerFactory.getLogger(getClass()), org.slf4j.event.Level.INFO,
                "unregistered-fake-event", "Fixed message", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankLoggingIdentityFailsStartupWithoutEchoingValues() {
        new ApplicationContextRunner().withUserConfiguration(LoggingConfig.class)
                .withPropertyValues("line.logging.service= ").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void configurationFailureRetainsOnlyKnownNamesInsteadOfCredentialValues() throws Exception {
        var event = event("org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter",
                "DATABASE_URL fake-private-address DATABASE_PASSWORD fake-private-password");
        var actual = mapper.readTree(new JsonLogFormatter(new MockEnvironment()).format(event));
        assertThat(actual.path("configuration_fields").toString()).contains("DATABASE_URL", "DATABASE_PASSWORD");
        assertThat(actual.toString()).doesNotContain("fake-private-address", "fake-private-password");
    }

    private LoggingEvent event(String name) {
        return event(name, "Fixed safe message");
    }

    private LoggingEvent event(String name, String message) {
        var event = new LoggingEvent(getClass().getName(), (Logger) LoggerFactory.getLogger(name), Level.INFO,
                message, null, null);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}
