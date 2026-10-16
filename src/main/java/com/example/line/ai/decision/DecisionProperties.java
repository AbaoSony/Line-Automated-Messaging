package com.example.line.ai.decision;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 定义 P2 时机判断、全局熔断及技术提示冷却的配置契约。
 * 模型名称由已有模型配置提供，不引入厂商对象；第三阶段运行配置显式选 RULE，MODEL 留待第六阶段。
 */
@ConfigurationProperties("line.ai.decision")
public record DecisionProperties(
        @DefaultValue("MODEL") Mode mode,
        @DefaultValue("low") String reasoning,
        @DefaultValue("2s") Duration attemptTimeout,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("1") int maxConcurrent,
        @DefaultValue("4096") int maxInputTokens,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("5") int failureThreshold,
        @DefaultValue("30s") Duration cooldown,
        @DefaultValue("10s") Duration technicalReplyCooldown) {

    private static final int MAX_ATTEMPTS = 2;
    private static final Duration MAX_ATTEMPT_TIMEOUT = Duration.ofSeconds(50);

    /** 模型判断与有界规则基线均保留聚合能力，不表示是否启用技术提示。 */
    public enum Mode { MODEL, RULE }

    /**
     * 校验判断额度和冷却时间；金额与费用目标不参与准入条件。
     * @throws IllegalArgumentException 配置不满足有限调用与冷却边界时抛出
     */
    public DecisionProperties {
        if (mode == null || reasoning == null
                || !Set.of("low", "medium", "high", "xhigh", "max").contains(reasoning)) {
            throw invalid("mode or reasoning is unsupported");
        }
        if (attemptTimeout == null || attemptTimeout.isNegative() || attemptTimeout.isZero()
                || attemptTimeout.compareTo(MAX_ATTEMPT_TIMEOUT) > 0) {
            throw invalid("attempt-timeout must be positive and not exceed 50s");
        }
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS || maxConcurrent < 1
                || maxInputTokens < 1 || maxOutputTokens < 1 || failureThreshold < 1) {
            throw invalid("request, concurrency and token bounds must be positive; max-attempts must not exceed 2");
        }
        validateCooldown(cooldown, "cooldown");
        validateCooldown(technicalReplyCooldown, "technical-reply-cooldown");
    }

    private static void validateCooldown(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw invalid(name + " must be positive");
        }
        // 后续定时使用纳秒，启动绑定时就拒绝溢出，不能等故障回退时才发现。
        try {
            value.toNanos();
        } catch (ArithmeticException failure) {
            throw invalid(name + " exceeds supported timer range");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid line.ai.decision configuration: " + reason);
    }
}
