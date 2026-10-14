package com.example.line.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定异步接收、内存幂等和内容下载的资源边界，分别供 WebhookConfig、
 * InMemoryEventDeduplicator 与 LineContentService 使用。
 * 来源为 application.yml 或外部 Spring 配置，没有专用环境变量占位符；
 * 所有参数必须有效，仓库已提供默认值，无需额外环境变量。
 *
 * @param workers 固定工作线程数，必须为正，默认 4；限制同时执行业务的任务数
 * @param queueCapacity 等待请求的容量，必须为正，默认 256；限制数量而不是请求总字节数
 * @param dedupTtl 成功事件保留时间，必须为正，默认 24 小时；从处理成功时开始计时
 * @param dedupMaxEntries 缓存容量，必须为正，默认 10000，包含处理中与未过期成功事件
 * @param contentMaxBytes 单次下载字节上限，必须为正，默认 10485760，即本项目的 10 MiB 防护值
 */
@ConfigurationProperties("line.webhook")
public record WebhookProperties(int workers, int queueCapacity, Duration dedupTtl,
                                int dedupMaxEntries, long contentMaxBytes) {
    /**
     * 阻止无效资源配置进入线程池、去重缓存或下载服务。
     *
     * @param workers 必须为正的固定业务线程数
     * @param queueCapacity 必须为正的等待任务容量
     * @param dedupTtl 必填且为正的成功去重保留时间
     * @param dedupMaxEntries 必须为正的缓存条目容量，包含处理中占用
     * @param contentMaxBytes 必须为正的单次内容字节上限
     * @throws IllegalArgumentException 任一数量非正或保留时间缺失、非正时抛出
     */
    public WebhookProperties {
        if (workers < 1 || queueCapacity < 1 || dedupMaxEntries < 1 || contentMaxBytes < 1
                || dedupTtl == null || dedupTtl.isNegative() || dedupTtl.isZero()) {
            throw new IllegalArgumentException("line.webhook requires positive workers, queue-capacity, "
                    + "dedup-ttl, dedup-max-entries and content-max-bytes");
        }
    }
}
