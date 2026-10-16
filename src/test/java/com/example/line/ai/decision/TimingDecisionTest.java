package com.example.line.ai.decision;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** 验证建议仅表达有界 NOW/WAIT 的基础语义，不授予模型发送或延期权限。 */
class TimingDecisionTest {
    @Test
    void nowRequiresZeroAndWaitRequiresFinitePositiveDuration() {
        assertThat(new TimingDecision(TimingDecision.Action.REPLY_NOW, Duration.ZERO, TimingDecision.Reason.URGENT).waitFor())
                .isZero();
        assertThatThrownBy(() -> new TimingDecision(TimingDecision.Action.REPLY_NOW, Duration.ofSeconds(1), TimingDecision.Reason.COMPLETE_INPUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimingDecision(TimingDecision.Action.WAIT_FOR_MORE, Duration.ZERO, TimingDecision.Reason.POSSIBLE_CONTINUATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimingDecision(TimingDecision.Action.WAIT_FOR_MORE, Duration.ofSeconds(-1), TimingDecision.Reason.POSSIBLE_CONTINUATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimingDecision(TimingDecision.Action.WAIT_FOR_MORE, Duration.ofSeconds(Long.MAX_VALUE), TimingDecision.Reason.POSSIBLE_CONTINUATION))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
