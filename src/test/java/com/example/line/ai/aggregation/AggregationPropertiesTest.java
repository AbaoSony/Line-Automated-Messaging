package com.example.line.ai.aggregation;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证聚合配置绑定及跨字段资源边界，不需要运行数据库或模型。 */
class AggregationPropertiesTest {
    @Test
    void defaultsReserveGenerationAndBoundMembersAcrossSnapshots() {
        var properties = bind(new MockEnvironment());
        assertThat(properties.collectionSpan()).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.queueReserve()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.dependencyReserve()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.generationReserve()).isEqualTo(Duration.ofSeconds(32));
        assertThat(properties.ruleWait()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.maxMembers()).isEqualTo(8);
        assertThat(properties.maxCharacters()).isEqualTo(4096);
        assertThat(properties.maxBufferedMembers()).isEqualTo(2048);
        assertThat(properties.maxBufferedCharacters()).isEqualTo(2_000_000);
        assertThat(properties.maxRegenerations()).isEqualTo(1);
        assertThat(properties.maxGenerationAttempts()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}={1} 违反聚合资源或时间边界")
    @CsvSource({
            "collection-span,0s", "collection-span,51s", "queue-reserve,-1s",
            "dependency-reserve,0s", "generation-reserve,51s", "rule-wait,7s",
            "max-members,0", "max-characters,0", "max-buffered-members,7",
            "max-buffered-characters,4095", "max-regenerations,-1", "max-regenerations,2",
            "max-generation-attempts,0", "max-generation-attempts,3", "max-generation-attempts,1"
    })
    void invalidConfigurationFailsDuringBinding(String key, String value) {
        var environment = new MockEnvironment().withProperty("line.ai.aggregation." + key, value);
        assertThatThrownBy(() -> bind(environment)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disablingRegenerationAllowsASinglePhysicalGenerationRequest() {
        var environment = new MockEnvironment()
                .withProperty("line.ai.aggregation.max-regenerations", "0")
                .withProperty("line.ai.aggregation.max-generation-attempts", "1");
        assertThat(bind(environment).maxGenerationAttempts()).isEqualTo(1);
        assertThat(bind(environment).maxRegenerations()).isZero();
    }

    private AggregationProperties bind(MockEnvironment environment) {
        return Binder.get(environment).bindOrCreate("line.ai.aggregation", AggregationProperties.class);
    }
}
