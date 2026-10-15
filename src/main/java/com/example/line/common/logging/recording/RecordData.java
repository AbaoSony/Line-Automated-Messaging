package com.example.line.common.logging.recording;

import com.example.line.common.logging.LogData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 为数据库事件和审计建立字段白名单；完整业务内容递归排除凭证，调用帧只留在 stdout。 */
public final class RecordData {
    private static final Set<String> COLUMNS = Set.of("ts", "event", "level", "trace_id", "webhook_event_id",
            "user_id", "outcome", "duration_ms", "http_status", "error_type", "message_type", "model",
            "input_tokens", "output_tokens", "service", "env", "version", "instance", "operator", "source_ip", "target_user_id");
    private static final Set<String> DETAIL = Set.of("operation", "attempt", "retry", "active", "queued", "shutdown",
            "message_id", "text_length", "content", "messages", "is_redelivery", "reason", "correlation_id", "reply_kind", "stage");

    private RecordData() {}

    /**
     * 仅复制允许字段与可持久化细节，过滤凭证和异常堆栈，不截断用户原文。
     * @param fields 结构化业务日志或审计字段
     * @return 脱离调用方对象的白名单 JSON 快照
     */
    public static Map<String, Object> snapshot(Map<String, ?> fields) {
        var result = new LinkedHashMap<String, Object>();
        var detail = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> {
            if (COLUMNS.contains(key)) result.put(key, LogData.business(value));
            if (DETAIL.contains(key)) detail.put(key, LogData.business(value));
        });
        result.put("detail", detail);
        return result;
    }
}
