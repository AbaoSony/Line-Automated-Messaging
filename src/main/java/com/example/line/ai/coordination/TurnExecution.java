package com.example.line.ai.coordination;

import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.BiConsumer;
import org.slf4j.MDC;

/** 在执行线程保留首条主 trace，并始终恢复线程原 MDC；错误交给协调器转为业务结果。 */
final class TurnExecution {
    private TurnExecution() {}

    static void execute(Supplier<TurnSlot.Call> begin, Predicate<TurnSlot.Call> current,
                        TurnWork work, BiConsumer<TurnSlot.Call, Boolean> ended) {
        TurnSlot.Call call = null; boolean failed = false;
        try {
            call = begin.get();
            if (call != null && current.test(call)) failed = run(call, work);
        } catch (RuntimeException failure) { failed = true; }
        finally {
            if (call != null) { call.detach(); call.snapshot.close(); }
            Thread.interrupted();
            ended.accept(call, failed);
        }
    }
    static boolean run(TurnSlot.Call call, TurnWork work) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            var first = call.snapshot.members().get(0);
            MDC.setContextMap(Map.of("trace_id", first.traceId(), "user_id", first.userId(), "webhook_event_id", first.eventId(),
                    "received_nanos", Long.toString(call.deadline.startedNanos()), "received_sequence", Long.toString(first.sequence()),
                    "accepted_at", first.receivedAt().toString()));
            work.execute(call.ticket, call.snapshot, call.deadline);
            return false;
        } catch (RuntimeException failure) {
            // 原异常可能携带外部信封；调用方必须把该结果分类为失败/回退，而不能假报成功。
            return true;
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
