package com.example.line.ai.decision;

import java.time.Duration;
import java.util.Objects;

/** 厂商无关的接话建议；只表达 NOW/WAIT，实际裁剪、版本与发送权限由协调器决定。 */
public record TimingDecision(Action action, Duration waitFor, Reason reason) {
    /** P2 允许的即时建议，不提供延迟 Push 或永久不回复动作。 */
    public enum Action { REPLY_NOW, WAIT_FOR_MORE }
    /** 固定业务原因，禁止以自由文本推理或命令代替。 */
    public enum Reason { COMPLETE_INPUT, POSSIBLE_CONTINUATION, USER_REQUESTED_WAIT, URGENT }

    /**
     * 验证基础建议语义；等待仍须由协调器裁剪到固定收集截止。
     * @throws NullPointerException 字段缺失时抛出
     * @throws IllegalArgumentException NOW 非零或 WAIT 非正时抛出
     */
    public TimingDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(waitFor, "waitFor");
        Objects.requireNonNull(reason, "reason");
        if (waitFor.isNegative() || action == Action.REPLY_NOW && !waitFor.isZero()
                || action == Action.WAIT_FOR_MORE && waitFor.isZero()) {
            throw new IllegalArgumentException("Invalid timing decision wait");
        }
        try { waitFor.toNanos(); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException("Timing wait exceeds timer range"); }
    }
}
