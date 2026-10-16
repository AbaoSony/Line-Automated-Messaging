package com.example.line.ai.aggregation;

import java.util.List;
import java.util.UUID;

/** 作用域内只读的有序业务快照；执行结束关闭后禁止复用，成员不含凭证或 SDK 对象。 */
public final class TurnSnapshot implements AutoCloseable {
    private final UUID turnId;
    private final long epoch, inputVersion, generationVersion;
    private List<InboundMember> members;
    private Runnable release;

    TurnSnapshot(UUID turnId, long epoch, long inputVersion, long generationVersion,
                 List<InboundMember> members, Runnable release) {
        this.turnId = turnId;
        this.epoch = epoch;
        this.inputVersion = inputVersion;
        this.generationVersion = generationVersion;
        this.members = List.copyOf(members);
        this.release = release;
    }

    /** @return 稳定轮次标识 */
    public UUID turnId() { return turnId; }
    /** @return 会话代次 */
    public long epoch() { return epoch; }
    /** @return 当前快照输入版本 */
    public long inputVersion() { return inputVersion; }
    /** @return 当前快照正文版本 */
    public long generationVersion() { return generationVersion; }
    /**
     * 在执行作用域内借用原文及每个成员各自的 trace，禁止关闭后继续持有或使用。
     * @return 不可修改的有序成员列表
     * @throws IllegalStateException 快照已关闭时抛出
     */
    public synchronized List<InboundMember> members() {
        if (release == null) throw new IllegalStateException("Turn snapshot already closed");
        return members;
    }
    /** 清除业务引用并归还快照持有权；网络取消但尚未实际结束时不得调用。 */
    @Override public synchronized void close() {
        if (release != null) {
            Runnable cleanup = release;
            release = null;
            members = List.of();
            cleanup.run();
        }
    }
}
