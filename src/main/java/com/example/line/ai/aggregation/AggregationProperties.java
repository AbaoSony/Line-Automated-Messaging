package com.example.line.ai.aggregation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 定义 P2 聚合的收集预留与容量契约，由生产轮次入口绑定并交给唯一会话协调器。
 */
@ConfigurationProperties("line.ai.aggregation")
public record AggregationProperties(
        @DefaultValue("6s") Duration collectionSpan,
        @DefaultValue("3s") Duration queueReserve,
        @DefaultValue("1s") Duration dependencyReserve,
        @DefaultValue("32s") Duration generationReserve,
        @DefaultValue("1s") Duration ruleWait,
        @DefaultValue("8") int maxMembers,
        @DefaultValue("4096") int maxCharacters,
        @DefaultValue("2048") int maxBufferedMembers,
        @DefaultValue("2000000") int maxBufferedCharacters,
        @DefaultValue("1") int maxRegenerations,
        @DefaultValue("2") int maxGenerationAttempts) {

    private static final Duration MAX_STAGE_DURATION = Duration.ofSeconds(50);
    private static final int MAX_REGENERATIONS = 1;
    private static final int MAX_GENERATION_ATTEMPTS = 2;

    /**
     * 拒绝无界容量、无效时长及无法容纳一次重生成的调用配置。
     * @throws IllegalArgumentException 配置违反有限资源或时间边界时抛出
     */
    public AggregationProperties {
        validateDuration(collectionSpan, "collection-span");
        validateDuration(queueReserve, "queue-reserve");
        validateDuration(dependencyReserve, "dependency-reserve");
        validateDuration(generationReserve, "generation-reserve");
        validateDuration(ruleWait, "rule-wait");
        if (ruleWait.compareTo(collectionSpan) > 0) {
            throw invalid("rule-wait must not exceed collection-span");
        }
        if (maxMembers < 1 || maxCharacters < 1 || maxBufferedMembers < maxMembers
                || maxBufferedCharacters < maxCharacters) {
            throw invalid("member/character capacities must be positive and fit global capacities");
        }
        if (maxRegenerations < 0 || maxRegenerations > MAX_REGENERATIONS
                || maxGenerationAttempts < 1 || maxGenerationAttempts > MAX_GENERATION_ATTEMPTS
                || maxRegenerations >= maxGenerationAttempts) {
            throw invalid("regenerations and generation attempts exceed shared request bounds");
        }
    }

    private static void validateDuration(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(MAX_STAGE_DURATION) > 0) {
            throw invalid(name + " must be positive and not exceed 50s");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid line.ai.aggregation configuration: " + reason);
    }
}
