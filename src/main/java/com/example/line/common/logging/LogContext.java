package com.example.line.common.logging;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** 以作用域管理请求与单个事件 MDC，与 TraceFilter 和 MdcTaskDecorator 协作防止线程复用串线。 */
public final class LogContext implements AutoCloseable {
    static final String BACKGROUND_TRACE_ID = UUID.randomUUID().toString();
    private final Map<String, String> previous;

    private LogContext() { previous = MDC.getCopyOfContextMap(); }

    /**
     * 建立新的服务端请求链路，不接受客户端提供的追踪标识。
     * @return 可自动恢复原上下文的请求作用域
     */
    public static LogContext request() {
        var scope = new LogContext();
        MDC.clear();
        MDC.put("trace_id", UUID.randomUUID().toString());
        MDC.put("event", LogEvents.HTTP_REQUEST_COMPLETED);
        return scope;
    }

    /**
     * 为单个 Webhook 事件设置用户和事件上下文，保留当前请求 trace_id。
     * @param webhookEventId 完整事件标识，缺失时清除该字段
     * @param userId 完整用户标识，缺失时清除该字段
     * @return 可自动恢复批次上下文的事件作用域
     */
    public static LogContext event(String webhookEventId, String userId) {
        var scope = new LogContext();
        if (MDC.get("trace_id") == null) MDC.put("trace_id", UUID.randomUUID().toString());
        putOrRemove("webhook_event_id", webhookEventId);
        putOrRemove("user_id", userId);
        MDC.put("event", LogEvents.WEBHOOK_RECEIVED);
        return scope;
    }

    /**
     * 为发送或落库补充用户上下文，保留已有事件与链路。
     * @param userId 完整用户标识，缺失时清除字段
     * @return 自动恢复的用户作用域
     */
    public static LogContext user(String userId) {
        var scope = new LogContext();
        if (MDC.get("trace_id") == null) MDC.put("trace_id", UUID.randomUUID().toString());
        putOrRemove("user_id", userId);
        return scope;
    }

    /**
     * 恢复进入作用域前的 MDC，避免用户标识留在复用线程中。
     */
    @Override
    public void close() { restore(previous); }

    static void restore(Map<String, String> context) {
        if (context == null) MDC.clear(); else MDC.setContextMap(context);
    }

    private static void putOrRemove(String name, String value) {
        if (value == null) MDC.remove(name); else MDC.put(name, value);
    }
}
