package com.example.line.common.logging;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/** 验证 local profile 同时保留 JSON stdout 与额外易读文件，二者遵守相同完整数据与凭证边界。 */
@SpringBootTest(properties = {"line.bot.channel-secret=fake-local-secret", "line.bot.channel-token=fake-local-token",
        "line.logging.readable-file=${java.io.tmpdir}/java-line-local-readable-test.log"})
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class LocalReadableLogIntegrationTest extends PostgresIntegrationSupport {
    @Value("${line.logging.readable-file}") private String readableFile;

    // 多个 Spring 测试上下文共用同一 Logback；必须解除上一个上下文的初始化标记才能验证 local 配置。
    @BeforeAll
    static void prepareProfileLogging() {
        LoggingSystem.get(LocalReadableLogIntegrationTest.class.getClassLoader()).cleanUp();
    }

    @AfterAll
    static void releaseProfileLogging() {
        LoggingSystem.get(LocalReadableLogIntegrationTest.class.getClassLoader()).cleanUp();
    }

    @Test
    void jsonConsoleAndReadableFileBothPreserveFullDataAndExcludeCredentials(CapturedOutput output) throws Exception {
        String marker = "fictional-log-" + UUID.randomUUID();
        try (var scope = LogContext.event(marker, "Ufictional-local-user")) {
            BusinessLog.write(LoggerFactory.getLogger(getClass()), Level.INFO, LogEvents.USER_MESSAGE_RECEIVED,
                    "Local logging verification", BusinessLog.fields("content", LogData.business(Map.of(
                            "text", marker + "\n虚构完整文本", "quoteToken", "fake-local-quote-token"))));
        }
        String consoleLine = output.getOut().lines().filter(line -> line.startsWith("{") && line.contains(marker)).findFirst().orElseThrow();
        var console = new ObjectMapper().readTree(consoleLine);
        assertThat(console.path("event").asText()).isEqualTo(LogEvents.USER_MESSAGE_RECEIVED);
        assertThat(console.path("content").path("text").asText()).isEqualTo(marker + "\n虚构完整文本");
        assertThat(console.path("user_id").asText()).isEqualTo("Ufictional-local-user");
        String localLine = Files.readAllLines(Path.of(readableFile)).stream().filter(line -> line.contains(marker)).findFirst().orElseThrow();
        assertThat(localLine).contains(" INFO ", "trace_id=" + console.path("trace_id").asText(), marker,
                "Ufictional-local-user", "虚构完整文本").doesNotContain("fake-local-quote-token");
        assertThat(consoleLine).doesNotContain("fake-local-quote-token", "fake-local-secret", "fake-local-token");
    }
}
