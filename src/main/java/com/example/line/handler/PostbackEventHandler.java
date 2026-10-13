package com.example.line.handler;

import com.linecorp.bot.webhook.model.PostbackEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PostbackEventHandler implements EventHandler<PostbackEvent> {
    private static final Logger log = LoggerFactory.getLogger(PostbackEventHandler.class);
    public Class<PostbackEvent> eventType() { return PostbackEvent.class; }
    public void handle(PostbackEvent event) {
        // Future business logic can read event.postback().data() here.
        log.info("LINE postback received eventId={}", event.webhookEventId());
    }
}
