package com.example.line.handler;

import com.linecorp.bot.webhook.model.UnfollowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UnfollowEventHandler implements EventHandler<UnfollowEvent> {
    private static final Logger log = LoggerFactory.getLogger(UnfollowEventHandler.class);
    public Class<UnfollowEvent> eventType() { return UnfollowEvent.class; }
    public void handle(UnfollowEvent event) {
        log.info("LINE unfollow userId={}, eventId={}",
                event.source() == null ? null : event.source().userId(), event.webhookEventId());
    }
}
