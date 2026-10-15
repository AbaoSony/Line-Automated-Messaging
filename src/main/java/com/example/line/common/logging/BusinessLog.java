package com.example.line.common.logging;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/** 集中补齐业务日志的事件、用户与追踪字段；失败仅输出异常类型和调用帧，避免异常消息夹带凭证。 */
public final class BusinessLog {
    private BusinessLog() {}

    /**
     * 记录登记事件及业务字段，固定消息使用参数占位符传入 SLF4J。
     * @param logger 当前职责类的 SLF4J 日志器
     * @param level 事件级别
     * @param event 已登记常量
     * @param message 固定安全摘要，不拼接 SDK 原始异常或请求体
     * @param fields 允许的业务字段
     * @param arguments 安全的 SLF4J 占位参数，不传凭证或原始异常
     * @throws IllegalArgumentException 事件未登记时抛出
     */
    public static void write(Logger logger, Level level, String event, String message, Map<String, ?> fields, Object... arguments) {
        if (!LogEvents.isRegistered(event)) throw new IllegalArgumentException("Unregistered log event");
        var builder = logger.atLevel(level).addKeyValue("event", event)
                .addKeyValue("trace_id", MDC.get("trace_id") == null ? LogContext.BACKGROUND_TRACE_ID : MDC.get("trace_id"))
                .addKeyValue("user_id", MDC.get("user_id"))
                .addKeyValue("webhook_event_id", MDC.get("webhook_event_id"));
        fields.forEach(builder::addKeyValue);
        builder.log(message, arguments);
    }

    /**
     * 记录失败的异常类型和完整调用帧，不输出 Throwable 消息或原始 toString。
     * @param logger 当前职责类的日志器
     * @param level 失败级别
     * @param event 已登记事件
     * @param message 固定安全摘要
     * @param failure 原始异常，仅提取类型与调用帧
     * @param fields 允许的上下文字段
     * @throws IllegalArgumentException 事件未登记时抛出
     */
    public static void failure(Logger logger, Level level, String event, String message,
                               Throwable failure, Map<String, ?> fields) {
        var all = new LinkedHashMap<String, Object>(fields);
        all.put("error_type", failure.getClass().getSimpleName());
        var frames = new ArrayList<String>();
        var seen = new IdentityHashMap<Throwable, Boolean>();
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (seen.put(current, true) != null) continue;
            frames.add(current.getClass().getName());
            StackTraceElement[] trace = current.getStackTrace();
            // SDK 代理或测试替身可能没有调用帧，日志不能覆盖原本应传播的发送异常。
            if (trace != null) for (StackTraceElement frame : trace) frames.add(frame.toString());
            if (current.getCause() != null) pending.addLast(current.getCause());
            Throwable[] suppressed = current.getSuppressed();
            if (suppressed != null) for (Throwable nested : suppressed) if (nested != null) pending.addLast(nested);
        }
        all.put("stack_trace", List.copyOf(frames));
        write(logger, level, event, message, all);
    }

    /**
     * 构造允许 null 值的结构化字段映射，避免缺失用户或状态码影响业务处理。
     * @param pairs 交替出现的字段名和字段值
     * @return 保持声明顺序的映射
     * @throws IllegalArgumentException 字段数量为奇数时抛出
     */
    public static Map<String, Object> fields(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Log fields must be key/value pairs");
        var fields = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) fields.put((String) pairs[index], pairs[index + 1]);
        return fields;
    }
}
