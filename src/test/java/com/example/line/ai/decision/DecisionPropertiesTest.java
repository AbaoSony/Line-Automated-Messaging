package com.example.line.ai.decision;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证时机判断与正文配置隔离，以及未来装配时的启动失败边界。 */
class DecisionPropertiesTest {
    @Test
    void decisionDefaultsStaySeparateFromBodyGenerationAndTechnicalReplySwitch() {
        var environment = new MockEnvironment()
                .withProperty("line.ai.model.reasoning", "max")
                .withProperty("line.ai.model.max-output-tokens", "4096")
                .withProperty("line.ai.technical-replies-enabled", "false");
        var properties = bind(environment);
        assertThat(properties.mode()).isEqualTo(DecisionProperties.Mode.MODEL);
        assertThat(properties.reasoning()).isEqualTo("low");
        assertThat(properties.attemptTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.maxAttempts()).isEqualTo(2);
        assertThat(properties.maxConcurrent()).isEqualTo(1);
        assertThat(properties.maxInputTokens()).isEqualTo(4096);
        assertThat(properties.maxOutputTokens()).isEqualTo(1024);
        assertThat(properties.failureThreshold()).isEqualTo(5);
        assertThat(properties.cooldown()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.technicalReplyCooldown()).isEqualTo(Duration.ofSeconds(10));
    }

    @ParameterizedTest(name = "{0}={1} 不能产生无界或无效的策略调用")
    @CsvSource({
            "reasoning,none", "attempt-timeout,0s", "attempt-timeout,51s",
            "max-attempts,0", "max-attempts,3", "max-concurrent,0",
            "max-input-tokens,0", "max-output-tokens,0", "failure-threshold,0",
            "cooldown,0s", "cooldown,-1s", "cooldown,999999999999999s",
            "technical-reply-cooldown,0s"
    })
    void invalidDecisionConfigurationFailsDuringBinding(String key, String value) {
        var environment = new MockEnvironment().withProperty("line.ai.decision." + key, value);
        assertThatThrownBy(() -> bind(environment)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ruleModeBindsWithoutChangingBodyReasoning() {
        var environment = new MockEnvironment().withProperty("line.ai.decision.mode", "RULE");
        assertThat(bind(environment).mode()).isEqualTo(DecisionProperties.Mode.RULE);
    }

    @Test
    void invalidConfigurationPreventsAContextThatExplicitlyEnablesDecisionPropertiesFromStarting() {
        new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("line.ai.decision.max-attempts=3")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    private DecisionProperties bind(MockEnvironment environment) {
        return Binder.get(environment).bindOrCreate("line.ai.decision", DecisionProperties.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DecisionProperties.class)
    static class TestConfiguration {}
}
