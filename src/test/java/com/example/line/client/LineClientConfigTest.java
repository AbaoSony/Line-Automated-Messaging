package com.example.line.client;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 LINE 客户端装配、必填配置快速失败以及报错不泄露凭证。 */
class LineClientConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LineClientConfig.class);

    // 场景：两项凭证齐全时能够创建唯一的消息客户端 Bean。
    @Test
    void createsClientWhenConfigured() {
        runner.withPropertyValues("line.bot.channel-secret=test-secret", "line.bot.channel-token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MessagingApiClient.class);
                    assertThat(context.getBean(LineProperties.class).getChannelSecret()).isEqualTo("test-secret");
                });
    }

    // 场景：内部 Push 启用但缺少独立鉴权值时启动失败。
    @Test
    void enabledInternalPushRequiresSeparateToken() {
        runner.withPropertyValues("line.bot.channel-secret=test-secret", "line.bot.channel-token=test-token",
                "line.internal-push.enabled=true")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "Set LINE_INTERNAL_PUSH_TOKEN when LINE_INTERNAL_PUSH_ENABLED=true"));
    }

    // 场景：两项 LINE 凭证均缺失时一次列出缺失配置名。
    @Test
    void reportsBothMissingProperties() {
        runner.run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("Missing required LINE configuration: line.bot.channel-secret (LINE_CHANNEL_SECRET), "
                        + "line.bot.channel-token (LINE_CHANNEL_TOKEN). Set non-blank environment variables or configuration properties."));
    }

    // 场景：Secret 为空白时拒绝启动，错误信息不包含已配置的 Access Token。
    @Test
    void rejectsBlankSecretWithoutDisclosingToken() {
        runner.withPropertyValues("line.bot.channel-secret=   ", "line.bot.channel-token=private-test-token")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Missing required LINE configuration: line.bot.channel-secret (LINE_CHANNEL_SECRET). "
                                + "Set non-blank environment variables or configuration properties.");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain("private-test-token");
                });
    }

    // 场景：Access Token 为空白时拒绝启动，错误信息不包含已配置的 Secret。
    @Test
    void rejectsBlankTokenWithoutDisclosingSecret() {
        runner.withPropertyValues("line.bot.channel-secret=private-test-secret", "line.bot.channel-token=   ")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Missing required LINE configuration: line.bot.channel-token (LINE_CHANNEL_TOKEN). "
                                + "Set non-blank environment variables or configuration properties.");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain("private-test-secret");
                });
    }

    private static String stackTrace(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
