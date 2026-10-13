package com.example.line.handler;

import com.linecorp.bot.webhook.model.Event;

public interface EventHandler<E extends Event> {
    Class<E> eventType();
    void handle(E event) throws Exception;
}
