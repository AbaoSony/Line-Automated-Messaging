package com.example.line.ai.decision;

import com.example.line.ai.aggregation.InboundMember;
import java.time.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

/** 虚构完整原文验证基线的明确提问、继续输入、急迫与台湾繁体词汇。 */
class RuleTimingPolicyTest {
    @ParameterizedTest
    @CsvSource({"等我一下,WAIT_FOR_MORE,USER_REQUESTED_WAIT", "先別回我還沒說完,WAIT_FOR_MORE,USER_REQUESTED_WAIT",
            "等一下我想問你？,WAIT_FOR_MORE,USER_REQUESTED_WAIT", "我今天有點累,WAIT_FOR_MORE,POSSIBLE_CONTINUATION",
            "你覺得呢？,REPLY_NOW,COMPLETE_INPUT", "就這些,REPLY_NOW,COMPLETE_INPUT", "緊急救命,REPLY_NOW,URGENT"})
    void rulesPreserveTextAndSuggestOneBoundedWaitOrImmediateReply(String text,TimingDecision.Action action,TimingDecision.Reason reason) {
        var member=new InboundMember("Ufictional-rule","fictional-event","fictional-message",text,Instant.EPOCH,Instant.EPOCH,1,"fictional-trace");
        var result=new RuleTimingPolicy().decide(List.of(member),Duration.ofSeconds(1));
        assertThat(result.action()).isEqualTo(action);assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.waitFor()).isEqualTo(action==TimingDecision.Action.REPLY_NOW?Duration.ZERO:Duration.ofSeconds(1));
        assertThat(member.text()).isEqualTo(text);
    }
}
