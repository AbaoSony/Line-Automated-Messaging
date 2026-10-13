package com.example.line.config;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LineClientConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LineClientConfig.class);

    @Test
    void createsClientWhenConfigured() {
        runner.withPropertyValues("line.bot.channel-secret=test-secret", "line.bot.channel-token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MessagingApiClient.class);
                    assertThat(context.getBean(LineProperties.class).getChannelSecret()).isEqualTo("test-secret");
                });
    }

    @Test
    void enabledInternalPushRequiresSeparateToken() {
        runner.withPropertyValues("line.bot.channel-secret=test-secret", "line.bot.channel-token=test-token",
                "line.internal-push.enabled=true")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "Set LINE_INTERNAL_PUSH_TOKEN when LINE_INTERNAL_PUSH_ENABLED=true"));
    }

    @Test
    void reportsBothMissingProperties() {
        runner.run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("Missing required LINE configuration: line.bot.channel-secret (LINE_CHANNEL_SECRET), "
                        + "line.bot.channel-token (LINE_CHANNEL_TOKEN). Set non-blank environment variables or configuration properties."));
    }

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
