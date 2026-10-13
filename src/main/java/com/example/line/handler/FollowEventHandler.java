package com.example.line.handler;

import com.linecorp.bot.webhook.model.FollowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FollowEventHandler implements EventHandler<FollowEvent> {
    private static final Logger log = LoggerFactory.getLogger(FollowEventHandler.class);
    public Class<FollowEvent> eventType() { return FollowEvent.class; }
    public void handle(FollowEvent event) {
        log.info("LINE follow userId={}, eventId={}",
                event.source() == null ? null : event.source().userId(), event.webhookEventId());
    }
}
