package com.example.line.ai.coordination;

/** P2 轮次状态；发送中只能按真实结果收尾，不能冒充未发送取消。 */
public enum TurnState {
    DECIDING, WAITING, READY, GENERATING, SENDING,
    ACCEPTED, FAILED, UNKNOWN, EXPIRED, CANCELLED, REJECTED;

    /** @return 是否已终结业务状态，实际在途执行仍需单独排空 */
    public boolean terminal() {
        return switch (this) {
            case ACCEPTED, FAILED, UNKNOWN, EXPIRED, CANCELLED, REJECTED -> true;
            default -> false;
        };
    }
}
