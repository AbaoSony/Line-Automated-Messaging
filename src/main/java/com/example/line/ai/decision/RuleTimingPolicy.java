package com.example.line.ai.decision;

import com.example.line.ai.aggregation.InboundMember;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** 规则基线只给出业务建议；等待次数、固定收集截止和版本由唯一协调器约束。 */
@Component
public class RuleTimingPolicy {
    /**
     * 对最新完整原文作保守判断，不改写或截断成员，不按标点分配多档等待。
     * @param members 有序的当前轮次成员
     * @param wait 可配置的一次规则等待上限
     * @return 急迫或明确完整问题立即回复，其余允许一次短等
     * @throws IllegalArgumentException 成员为空时抛出
     */
    public TimingDecision decide(List<InboundMember> members, Duration wait) {
        if (members.isEmpty()) throw new IllegalArgumentException("Missing turn members");
        String latest = members.get(members.size() - 1).text().strip();
        if (contains(latest, "救命", "緊急", "紧急", "馬上回答", "马上回答", "快回答"))
            return now(TimingDecision.Reason.URGENT);
        if (contains(latest, "等我", "等一下", "先別回", "先别回", "還沒說完", "还没说完", "我再補", "我再补"))
            return new TimingDecision(TimingDecision.Action.WAIT_FOR_MORE, wait, TimingDecision.Reason.USER_REQUESTED_WAIT);
        if (latest.endsWith("？") || latest.endsWith("?") || contains(latest, "說完了", "说完了", "就這些", "就这些", "請回答", "请回答"))
            return now(TimingDecision.Reason.COMPLETE_INPUT);
        return new TimingDecision(TimingDecision.Action.WAIT_FOR_MORE, wait, TimingDecision.Reason.POSSIBLE_CONTINUATION);
    }
    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
    private TimingDecision now(TimingDecision.Reason reason) {
        return new TimingDecision(TimingDecision.Action.REPLY_NOW, Duration.ZERO, reason);
    }
}
