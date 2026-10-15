package com.example.line.common.logging.recording;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 定义日志独立连接池和内存批写队列的资源边界，供 EventRecorder 与数据源装配共同使用。 */
@ConfigurationProperties("line.event-recorder")
public record RecorderProperties(@DefaultValue("4096") int queueCapacity,
                                 @DefaultValue("100") int batchSize,
                                 @DefaultValue("1s") Duration flushInterval,
                                 @DefaultValue("2") int poolSize,
                                 @DefaultValue("1000") long connectionTimeoutMs,
                                 @DefaultValue("3") int queryTimeoutSeconds) {
    /**
     * 校验队列、批次、超时和连接池边界。
     * @param queueCapacity 最大排队记录数
     * @param batchSize 单批记录数
     * @param flushInterval 最长批次等待间隔
     * @param poolSize 日志连接池最大连接数
     * @param connectionTimeoutMs 获取日志连接的超时毫秒数
     * @param queryTimeoutSeconds 日志 SQL 执行超时秒数
     * @throws IllegalArgumentException 配置非正数或连接超时低于 Hikari 下限时抛出
     */
    public RecorderProperties {
        if (queueCapacity < 1 || batchSize < 1 || poolSize < 1 || connectionTimeoutMs < 250
                || queryTimeoutSeconds < 1 || flushInterval == null || flushInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid line.event-recorder resource limits");
        }
    }
}
