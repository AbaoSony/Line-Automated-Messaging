package com.example.line.common.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/** 在提交时复制 MDC，在任务结束或异常时恢复工作线程上下文，供 Webhook 与落库线程池共用。 */
public final class MdcTaskDecorator implements TaskDecorator {
    /**
     * 包装异步任务；后台任务没有请求上下文时建立独立链路标识。
     * @param runnable 待执行任务
     * @return 携带提交时快照并可靠清理 MDC 的任务
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> caller = MDC.getCopyOfContextMap();
        Map<String, String> snapshot = caller == null ? new HashMap<>() : new HashMap<>(caller);
        snapshot.computeIfAbsent("trace_id", ignored -> UUID.randomUUID().toString());
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(snapshot);
                runnable.run();
            } finally {
                LogContext.restore(previous);
            }
        };
    }
}
