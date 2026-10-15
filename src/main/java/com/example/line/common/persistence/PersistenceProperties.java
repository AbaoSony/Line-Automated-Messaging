package com.example.line.common.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 约束异步落库资源与清理批次，由 PersistenceConfig 与业务保留任务共同使用。 */
@ConfigurationProperties("line.persistence")
public record PersistenceProperties(int workers, int queueCapacity, Duration shutdownTimeout, int cleanupBatchSize) {
    /**
     * 校验有界执行资源，避免配置导致无界排队或无效清理。
     * @param workers 固定工作线程数
     * @param queueCapacity 排队任务上限
     * @param shutdownTimeout 停机等待预算
     * @param cleanupBatchSize 单批删除上限
     * @throws IllegalArgumentException 任一资源边界不合法时抛出
     */
    public PersistenceProperties {
        if (workers < 1 || queueCapacity < 1 || shutdownTimeout == null
                || shutdownTimeout.isNegative() || shutdownTimeout.isZero() || cleanupBatchSize < 1) {
            throw new IllegalArgumentException("Invalid line.persistence resource configuration");
        }
    }
}
