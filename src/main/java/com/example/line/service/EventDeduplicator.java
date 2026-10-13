package com.example.line.service;

/**
 * Redis implementations must acquire atomically and retain in-flight reservations.
 * 定义 WebhookService 的幂等生命周期：抢占、成功保留、失败释放。
 * 当前由 InMemoryEventDeduplicator 实现；替换 Redis 时必须保持原子占用和处理中保护。
 */
public interface EventDeduplicator {
    /**
     * 原子抢占尚未被处理或成功保留的事件标识。
     *
     * @param eventId 已由接入服务检查为非空白的 webhookEventId，而非 messageId
     * @return true 表示取得本次处理资格；false 表示正在处理或成功记录尚未过期
     * @throws IllegalStateException 实现无法接受新占用，例如内存缓存达到容量时抛出
     */
    boolean tryAcquire(String eventId);
    /**
     * 将成功事件从处理中切换为按 TTL 保留，阻止重投递再次产生副作用。
     *
     * @param eventId 当前调用已成功抢占并完成业务的事件标识
     */
    void complete(String eventId);
    /**
     * 释放失败任务所占用的标识，使未来再次投递有机会执行。
     *
     * @param eventId 当前失败调用持有的事件标识；不得释放其他任务的占用
     */
    void release(String eventId);
}
