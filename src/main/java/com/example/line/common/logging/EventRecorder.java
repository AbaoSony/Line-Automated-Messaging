package com.example.line.common.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.example.line.common.logging.recording.LogRecordStore;
import com.example.line.common.logging.recording.RecordData;
import com.example.line.common.logging.recording.RecordQueue;
import com.example.line.common.logging.recording.RecorderProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 在应用日志边界接收已登记事件并异步批写；所有业务包复用，不依赖审计或其他业务包。 */
@Component
@DependsOn("flywayInitializer")
public final class EventRecorder extends UnsynchronizedAppenderBase<ILoggingEvent> implements SmartLifecycle {
    private static final String APPENDER_NAME = "LINE_EVENT_RECORDER";
    private final RecordQueue queue;
    private final LogFields fields;
    private final MeterRegistry meters;
    private final ch.qos.logback.classic.Logger applicationLogger;

    /**
     * 在迁移完成后装配独立日志队列，启动期框架消息只保留 stdout。
     * @param store 独立日志池批写存储
     * @param properties 有界队列配置
     * @param meters 指标注册表
     * @param environment 日志身份配置来源
     * @param tracker 用户删除工作跟踪
     */
    @org.springframework.beans.factory.annotation.Autowired
    public EventRecorder(LogRecordStore store, RecorderProperties properties, MeterRegistry meters, Environment environment,
                         com.example.line.common.execution.UserWorkTracker tracker) {
        this.queue = new RecordQueue(store, properties, meters,tracker);
        this.fields = new LogFields(environment);
        this.meters = meters;
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        setContext(context);
        setName(APPENDER_NAME);
        applicationLogger = context.getLogger("com.example.line");
        for (String name : java.util.List.of("webhook.requests", "webhook.signature.failed", "webhook.dedup.hits",
                "line.reply.failed", "line.push.failed", "webhook.rejected", "persistence.rejected")) meters.counter(name);
    }

    /**
     * 供独立日志生命周期测试使用隔离的用户工作跟踪器。
     * @param store 测试记录存储
     * @param properties 测试队列配置
     * @param meters 测试指标
     * @param environment 测试日志身份
     */
    public EventRecorder(LogRecordStore store,RecorderProperties properties,MeterRegistry meters,Environment environment){
        this(store,properties,meters,environment,new com.example.line.common.execution.UserWorkTracker());
    }

    /** 启动写入线程并挂接应用日志，框架日志仍只走原有 JSON stdout。 */
    @Override
    public void start() {
        if (isRunning()) return;
        queue.start();
        super.start();
        applicationLogger.detachAppender(APPENDER_NAME);
        applicationLogger.addAppender(this);
    }

    /** 先等业务执行器停机，再停止接收日志并刷完队列；连接池随后由 Spring 销毁。 */
    @Override
    public void stop() {
        applicationLogger.detachAppender(this);
        super.stop();
        queue.close();
    }

    /** @return 日志接收器是否已启动 */
    @Override
    public boolean isRunning() { return isStarted(); }

    /** @return 低于业务线程池的阶段，保证业务队列先关闭、日志队列后刷完 */
    @Override
    public int getPhase() { return 0; }

    /**
     * 接收审计服务提供的已登记操作，与事件队列共享独立日志连接池。
     * @param auditFields 不含凭证的完整审计字段
     * @return 接收成功返回 true，满队列丢弃并报告 persist.failed 时返回 false
     * @throws IllegalArgumentException 不是已登记审计事件时抛出
     */
    public boolean audit(Map<String, ?> auditFields) {
        Object event = auditFields.get("event");
        if (!LogEvents.INTERNAL_PUSH_INVOKED.equals(event) && !LogEvents.USER_DATA_DELETED.equals(event)) {
            throw new IllegalArgumentException("Unregistered audit event");
        }
        var data = RecordData.snapshot(auditFields);
        fields.identify(data);
        data.put("level", "FAILED".equals(data.get("outcome")) ? "ERROR" : "INFO");
        return queue.offer(true, data);
    }

    /** @return 日志队列无待写入或执行中的记录时返回 true */
    public boolean isIdle() { return queue.isIdle(); }

    /**
     * 只接收显式登记的结构化业务事件，排除自身写入失败以打断递归。
     * @param event SLF4J 事件，原始异常消息不会进入事件表
     */
    @Override
    protected void append(ILoggingEvent event) {
        if (event.getKeyValuePairs() == null || event.getKeyValuePairs().stream().noneMatch(pair ->
                pair.key.equals("event") && pair.value instanceof String name && LogEvents.isRegistered(name))) return;
        Map<String, Object> data = fields.extract(event);
        String name = (String) data.get("event");
        if (name.equals(LogEvents.PERSIST_FAILED)) return;
        count(name, event.getLoggerName());
        queue.offer(false, RecordData.snapshot(data));
    }

    private void count(String event, String logger) {
        if (logger.endsWith("WebhookController") && (event.equals(LogEvents.WEBHOOK_RECEIVED)
                || event.equals(LogEvents.WEBHOOK_SIGNATURE_INVALID))) meters.counter("webhook.requests").increment();
        String metric = switch (event) {
            case LogEvents.WEBHOOK_SIGNATURE_INVALID -> "webhook.signature.failed";
            case LogEvents.WEBHOOK_DUPLICATE -> "webhook.dedup.hits";
            case LogEvents.LINE_REPLY_FAILED -> "line.reply.failed";
            case LogEvents.LINE_PUSH_FAILED -> "line.push.failed";
            default -> null;
        };
        if (metric != null) meters.counter(metric).increment();
    }
}
