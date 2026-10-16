package com.example.line.ai.coordination;

import java.util.UUID;

/** 由程序绑定的会话、输入、正文和调用版本，模型无法自行声明某个回调有效。 */
public record TurnTicket(String userId, long epoch, UUID turnId, long inputVersion,
                         long generationVersion, long callSequence, Kind kind) {
    /** 规则判断不消耗模型物理请求额度；策略与正文各有共享调用边界。 */
    public enum Kind { RULE, DECISION, GENERATION }
}
