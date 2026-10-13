package com.example.line.service;

import com.example.line.handler.EventHandler;
import com.linecorp.bot.webhook.model.Event;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将 Spring 收集的 EventHandler 注册为事件类型到策略的映射，供 WebhookService 调用。
 * Controller 不需要了解具体 SDK 事件；重复类型在装配时失败，防止业务处理选择不明确。
 */
@Service
public class EventDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
    private final Map<Class<?>, EventHandler<?>> handlers = new HashMap<>();

    /**
     * 建立唯一策略注册表，运行时按事件的具体 Class 查找，不做父类型兜底匹配。
     *
     * @param handlers Spring 提供的 Handler 集合；每个实现须声明不同且非 null 的事件类型
     * @throws IllegalArgumentException 多个 Handler 声明同一事件类型时抛出
     */
    public EventDispatcher(List<EventHandler<?>> handlers) {
        for (var handler : handlers) {
            if (this.handlers.putIfAbsent(handler.eventType(), handler) != null) {
                throw new IllegalArgumentException("Duplicate handler for " + handler.eventType().getSimpleName());
            }
        }
    }

    /**
     * 将已验签、已去重的事件交给匹配策略；未知但可解析的类型仅记录追踪日志。
     *
     * @param event 非 null 的 SDK 事件，由异步接入服务提供
     * @throws Exception Handler 执行失败时向上层传播，由接入服务释放幂等占用
     */
    public void dispatch(Event event) throws Exception {
        var handler = handlers.get(event.getClass());
        if (handler == null) {
            log.info("Unsupported LINE event type={}, eventId={}", event.getClass().getSimpleName(), event.webhookEventId());
            return;
        }
        invoke(handler, event);
    }

    private <E extends Event> void invoke(EventHandler<E> handler, Event event) throws Exception {
        handler.handle(handler.eventType().cast(event));
    }
}
