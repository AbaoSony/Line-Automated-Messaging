package com.example.line.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/** 通过 Spring Boot 内置 StructuredLogEncoder 输出固定字段的单行 JSON，供所有环境 stdout 使用。 */
public final class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private final LogFields fields;

    /**
     * 在日志启动期绑定固定身份字段，无需新增日志依赖。
     * @param environment Spring Boot 提供的运行环境
     * @throws IllegalArgumentException 日志配置为空白时抛出
     */
    public JsonLogFormatter(Environment environment) { fields = new LogFields(environment); }

    /**
     * 输出一个完整 JSON 记录，消息换行由 JSON 编码，防止伪造额外日志行。
     * @param event Logback 日志事件
     * @return 包含单个末尾换行的 JSON
     */
    @Override
    public String format(ILoggingEvent event) { return LogFields.JSON.writeToString(fields.extract(event)) + "\n"; }
}
