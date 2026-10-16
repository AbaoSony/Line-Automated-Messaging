package com.example.line.ai.coordination;

import java.util.concurrent.atomic.AtomicBoolean;

/** 区分尚未实际开始与已经执行的作业；取消排队作业必须正常归还预占资源和完成阶段。 */
final class TurnJob implements Runnable {
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final Runnable action, aborted;

    TurnJob(Runnable action, Runnable aborted) { this.action = action; this.aborted = aborted; }

    /** 只有实际执行与排队取消之一可以取得作业，迟到的队列回调不再次归还资源。 */
    @Override public void run() { if (claimed.compareAndSet(false, true)) action.run(); }

    boolean cancelQueued() {
        if (!claimed.compareAndSet(false, true)) return false;
        aborted.run(); return true;
    }
}
