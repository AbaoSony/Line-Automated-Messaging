package com.example.line.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/** 为 local profile 的额外文件生成时间、级别和链路前缀，共用 JSON 的完整业务数据与凭证边界。 */
public final class ReadableLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private final LogFields fields;

    /**
     * 绑定与 stdout 相同的日志配置。
     * @param environment Spring Boot 提供的运行环境
     */
    public ReadableLogFormatter(Environment environment) { fields = new LogFields(environment); }

    /**
     * 输出易读前缀和完整详细字段，用户换行仍转义为单条记录。
     * @param event Logback 日志事件
     * @return 带时间、级别、trace_id、event 及完整字段的文本行
     */
    @Override
    public String format(ILoggingEvent event) {
        var values = fields.extract(event);
        return "%s %-5s trace_id=%s event=%s %s%n".formatted(values.get("ts"), values.get("level"),
                values.get("trace_id"), values.get("event"), LogFields.JSON.writeToString(values));
    }
}
