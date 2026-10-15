package com.example.line.ai;

import com.example.line.ai.generation.ModelProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

/** 验证放宽模型等待后的默认预算与整体上限，不访问实际模型。 */
class AiBudgetPropertiesTest {
    @Test void defaultBudgetsAllowOneLongerAttemptAndKeepReplyReserve() {
        var binder=Binder.get(new MockEnvironment());
        var ai=binder.bindOrCreate("line.ai",AiProperties.class);
        var model=binder.bindOrCreate("line.ai.model",ModelProperties.class);
        assertThat(ai.totalTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(ai.sendReserve()).isEqualTo(Duration.ofSeconds(3));
        assertThat(ai.technicalRepliesEnabled()).isFalse();
        assertThat(model.totalTimeout()).isEqualTo(Duration.ofSeconds(40));
        assertThat(model.attemptTimeout()).isEqualTo(Duration.ofSeconds(40));
        assertThat(model.maxOutputTokens()).isEqualTo(4096);
        assertThat(model.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(model.totalTimeout().plus(ai.sendReserve())).isLessThan(ai.totalTimeout());
    }

    @Test void technicalRepliesCanBeExplicitlyEnabledWithoutChangingGenerationBudget() {
        var env=new MockEnvironment().withProperty("line.ai.technical-replies-enabled","true");
        var properties=Binder.get(env).bindOrCreate("line.ai",AiProperties.class);
        assertThat(properties.technicalRepliesEnabled()).isTrue();
        assertThat(properties.totalTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test void totalBudgetCanReach50SecondsButRejectsLargerValues() {
        var maximum=new MockEnvironment().withProperty("line.ai.total-timeout","50s");
        assertThat(Binder.get(maximum).bindOrCreate("line.ai",AiProperties.class).totalTimeout()).isEqualTo(Duration.ofSeconds(50));
        var excessive=new MockEnvironment().withProperty("line.ai.total-timeout","51s");
        assertThatThrownBy(() -> Binder.get(excessive).bindOrCreate("line.ai",AiProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
