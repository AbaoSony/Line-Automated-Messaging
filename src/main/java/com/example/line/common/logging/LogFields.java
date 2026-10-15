package com.example.line.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.json.JsonWriter;
import org.springframework.core.env.Environment;

/** 为 JSON 与易读格式器构造同一份固定字段和白名单数据，不依赖业务包或 Spring Bean 初始化顺序。 */
final class LogFields {
    static final JsonWriter<Map<String, Object>> JSON = JsonWriter.of(members -> members.add().usingPairs(Map::forEach));
    private static final Set<String> ALLOWED = Set.of("webhook_event_id", "user_id", "outcome", "duration_ms",
            "http_status", "error_type", "message_type", "model", "input_tokens", "output_tokens", "operation",
            "attempt", "retry", "active", "queued", "shutdown", "message_id", "text_length", "content",
            "messages", "is_redelivery", "reason", "stack_trace");
    private final LoggingProperties properties;
    private static final Set<String> CONFIGURATION_NAMES = Set.of("LINE_CHANNEL_SECRET", "LINE_CHANNEL_TOKEN",
            "LINE_INTERNAL_PUSH_TOKEN", "DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD",
            "line.logging.service", "line.logging.env", "line.logging.version", "line.logging.instance", "line.logging.readable-file");

    LogFields(Environment environment) {
        properties = Binder.get(environment).bindOrCreate("line.logging", LoggingProperties.class);
    }

    Map<String, Object> extract(ILoggingEvent event) {
        var supplied = new LinkedHashMap<String, Object>(event.getMDCPropertyMap());
        if (event.getKeyValuePairs() != null) event.getKeyValuePairs().forEach(pair -> supplied.put(pair.key, pair.value));
        var result = new LinkedHashMap<String, Object>();
        result.put("ts", event.getInstant().toString());
        result.put("level", event.getLevel().toString());
        result.put("service", properties.service());
        result.put("env", properties.env());
        result.put("version", properties.version());
        result.put("instance", properties.instance());
        Object traceId = supplied.get("trace_id");
        result.put("trace_id", traceId == null ? LogContext.BACKGROUND_TRACE_ID : traceId);
        Object name = supplied.get("event");
        boolean application = event.getLoggerName().startsWith("com.example.line.");
        result.put("event", application && name instanceof String value && LogEvents.isRegistered(value)
                ? value : LogEvents.APP_LIFECYCLE);
        result.put("user_id", supplied.get("user_id"));
        result.put("logger", event.getLoggerName());
        result.put("thread", event.getThreadName());
        // 第三方框架的异常摘要可能回显绑定值、数据库地址或请求片段，仅保留组件和调用帧。
        result.put("message", application ? event.getFormattedMessage() : "Framework lifecycle event");
        var configurationFields = new LinkedHashSet<String>();
        if (!application) collectConfigurationNames(event.getFormattedMessage(), configurationFields);
        supplied.forEach((key, value) -> {
            if (ALLOWED.contains(key)) result.put(key, LogData.business(value));
        });
        if (event.getThrowableProxy() != null) {
            var frames = new ArrayList<String>();
            var pending = new ArrayDeque<IThrowableProxy>();
            var seen = new IdentityHashMap<IThrowableProxy, Boolean>();
            pending.add(event.getThrowableProxy());
            while (!pending.isEmpty()) {
                IThrowableProxy cause = pending.removeFirst();
                if (cause.isCyclic() || seen.put(cause, true) != null) continue;
                frames.add(cause.getClassName());
                if (!application) collectConfigurationNames(cause.getMessage(), configurationFields);
                if (cause.getStackTraceElementProxyArray() != null) {
                    for (var frame : cause.getStackTraceElementProxyArray()) frames.add(frame.getStackTraceElement().toString());
                }
                if (cause.getCause() != null) pending.addLast(cause.getCause());
                if (cause.getSuppressed() != null) for (var nested : cause.getSuppressed()) pending.addLast(nested);
            }
            result.put("error_type", event.getThrowableProxy().getClassName());
            result.put("stack_trace", frames);
        }
        if (!configurationFields.isEmpty()) {
            result.put("configuration_fields", configurationFields);
            if (!application) result.put("message", "Configuration validation failed; check named environment variables or properties");
        }
        return result;
    }

    private static void collectConfigurationNames(String text, Set<String> names) {
        // 启动失败仍须提示配置名，但禁止直接输出框架可能回显配置值的完整错误分析。
        if (text != null) for (String name : CONFIGURATION_NAMES) if (text.contains(name)) names.add(name);
    }
}
