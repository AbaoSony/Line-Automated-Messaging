package com.example.line.ai.coordination;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 管理每轮最多三个定时引用及版本，控制回调回到唯一状态所有者，不执行外部 I/O。 */
final class TurnClock {
    private final TurnTimer timer;
    private final Consumer<Runnable> control;
    private final Predicate<TurnSlot> live;
    private final Consumer<TurnSlot> rejected;

    TurnClock(TurnTimer timer, Consumer<Runnable> control, Predicate<TurnSlot> live, Consumer<TurnSlot> rejected) {
        this.timer = timer; this.control = control; this.live = live; this.rejected = rejected;
    }

    void bounds(TurnSlot turn, Runnable collect, Runnable expire) {
        if (turn.collectionTimer != null) turn.collectionTimer.cancel();
        if (turn.expiryTimer != null) turn.expiryTimer.cancel();
        turn.collectionTimer = schedule(turn, turn.budget.collectionDeadline().expiresNanos(), () -> {
            if (live.test(turn) && !turn.state.terminal()) collect.run();
        });
        if (turn.state.terminal()) return;
        turn.expiryTimer = schedule(turn, turn.budget.totalDeadline().expiresNanos(), () -> {
            if (live.test(turn) && !turn.state.terminal()) expire.run();
        });
    }

    void alarm(TurnSlot turn, long at, Runnable action) {
        cancelAlarm(turn); long version = turn.alarmVersion;
        turn.alarmTimer = schedule(turn, at, () -> {
            if (live.test(turn) && !turn.state.terminal() && turn.alarmVersion == version) action.run();
        });
    }

    void cancelAlarm(TurnSlot turn) {
        if (turn.alarmTimer != null) turn.alarmTimer.cancel();
        turn.alarmTimer = null; turn.alarmVersion++;
    }

    private TurnTimer.Cancellation schedule(TurnSlot turn, long at, Runnable action) {
        try { return timer.schedule(at, () -> control.accept(action)); }
        catch (RejectedExecutionException failure) { rejected.accept(turn); return () -> {}; }
    }
}
