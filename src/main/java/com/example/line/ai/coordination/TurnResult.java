package com.example.line.ai.coordination;

/** 已准入轮次的明确业务终态；以正常完成阶段返回，不把业务失败变成允许重投递重新发送。 */
public record TurnResult(TurnState state, Reason reason) {
    /** 固定安全原因，不承载异常消息、SDK 对象或发送凭证。 */
    public enum Reason {
        NORMAL, CAPACITY, INPUT_TOO_LONG, OUT_OF_ORDER, CLOSED, CANCELLED,
        DEADLINE, QUEUE_TIMEOUT, TECHNICAL_SILENT, WORK_FAILURE, UNFINISHED_WORK, SEND_FAILURE, SEND_UNKNOWN
    }

    /**
     * 拒绝把执行中状态或缺失原因伪造为业务完成。
     * @throws IllegalArgumentException 结果不是终态时抛出
     */
    public TurnResult {
        if (state == null || !state.terminal() || reason == null) {
            throw new IllegalArgumentException("Invalid terminal turn result");
        }
    }
}
