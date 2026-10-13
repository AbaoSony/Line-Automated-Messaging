package com.example.line.handler;

import com.linecorp.bot.webhook.model.Event;

/**
 * 定义事件策略契约，由 EventDispatcher 收集 Spring Handler 并按 SDK 事件类型分发。
 * 新增实现只需注册为 Bean，不要求修改 WebhookController 或异步接入流程。
 *
 * @param <E> 本 Handler 接受的具体 LINE SDK 事件类型
 */
public interface EventHandler<E extends Event> {
    /**
     * 声明注册分发所使用的具体事件类型。
     *
     * @return 非 null 的事件 Class，同一 Class 不得注册多个 Handler
     */
    Class<E> eventType();
    /**
     * 在已验签、已取得幂等占用的工作线程中执行该类事件的业务。
     *
     * @param event 非 null 且类型与 eventType 匹配的 SDK 事件
     * @throws Exception 业务执行或发送失败时向 WebhookService 传播，便于释放幂等占用
     */
    void handle(E event) throws Exception;
}
