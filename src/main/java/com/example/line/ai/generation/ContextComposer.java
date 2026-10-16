package com.example.line.ai.generation;

import com.example.line.chat.PrivateHistory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 以 UTF-8 字节上界和角色开销保守控制文本 token 预算，仅移除完整旧轮，不改原始记录。 */
@Component
public class ContextComposer {
    private static final int MESSAGE_OVERHEAD = 64;
    private final ModelProperties properties;
    /**
     * 注入模型输入预算，字节级计数刻意保守，不声称是供应商精确计费量。
     * @param properties 输入 token 上限
     */
    public ContextComposer(ModelProperties properties) { this.properties = properties; }
    /**
     * 保留人设与当前输入，将最旧完整轮次依次移除至保守预算内。
     * @param prompt 完整人设
     * @param current 当前用户原文
     * @param turns 时间正序完整轮次
     * @return 按原角色组织的历史列表，当前输入由模型适配器另加一次
     * @throws ModelFailure 人设加当前输入本身超限时抛出
     */
    public List<ChatRequest.HistoryMessage> compose(String prompt, String current, List<PrivateHistory.Turn> turns) {
        return composeMembers(prompt, List.of(current), turns);
    }
    /**
     * 每个当前成员分别计入角色开销，超限只裁剪完整旧轮，不删除当前成员。
     * @param prompt 完整人设
     * @param current 全部有序用户原文
     * @param turns 已接受的完整旧轮
     * @return 保留的完整历史，当前成员由适配器逐条加入
     * @throws ModelFailure 当前成员及人设已经超限时抛出
     */
    public List<ChatRequest.HistoryMessage> composeMembers(String prompt, List<String> current, List<PrivateHistory.Turn> turns) {
        long base = cost(prompt);
        for (String text : current) base += cost(text);
        if (base > properties.maxInputTokens()) throw new ModelFailure("input_too_long");
        int start = turns.size();
        long used = base;
        while (start > 0) {
            var turn = turns.get(start-1);
            long next = cost(turn.assistantText());
            for(var member:turn.members())next+=cost(member.text());
            if (used + next > properties.maxInputTokens()) break;
            used += next; start--;
        }
        var result = new ArrayList<ChatRequest.HistoryMessage>();
        for (var turn : turns.subList(start, turns.size())) {
            for(var member:turn.members())result.add(new ChatRequest.HistoryMessage("user",member.text()));
            result.add(new ChatRequest.HistoryMessage("assistant", turn.assistantText()));
        }
        return List.copyOf(result);
    }
    private int cost(String text) { return text.getBytes(StandardCharsets.UTF_8).length + MESSAGE_OVERHEAD; }
}
