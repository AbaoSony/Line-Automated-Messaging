package com.example.line.service;

import com.example.line.handler.EventHandler;
import com.linecorp.bot.webhook.model.Event;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EventDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
    private final Map<Class<?>, EventHandler<?>> handlers = new HashMap<>();

    public EventDispatcher(List<EventHandler<?>> handlers) {
        for (var handler : handlers) {
            if (this.handlers.putIfAbsent(handler.eventType(), handler) != null) {
                throw new IllegalArgumentException("Duplicate handler for " + handler.eventType().getSimpleName());
            }
        }
    }

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
