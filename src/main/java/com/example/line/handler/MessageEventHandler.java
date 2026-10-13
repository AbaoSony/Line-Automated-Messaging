package com.example.line.handler;

import com.example.line.service.LineMessageService;
import com.linecorp.bot.webhook.model.EventMode;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessageEventHandler implements EventHandler<MessageEvent> {
    private static final Logger log = LoggerFactory.getLogger(MessageEventHandler.class);
    private final LineMessageService messages;

    public MessageEventHandler(LineMessageService messages) {
        this.messages = messages;
    }

    @Override
    public Class<MessageEvent> eventType() {
        return MessageEvent.class;
    }

    @Override
    public void handle(MessageEvent event) throws Exception {
        if (event.message() == null) {
            throw new IllegalArgumentException("Missing message content");
        }
        if (event.mode() == EventMode.STANDBY) {
            log.info("Skipping reply for standby eventId={}", event.webhookEventId());
            return;
        }
        if (event.message() instanceof TextMessageContent text) {
            messages.replyText(event.replyToken(), text.text());
        } else {
            // Includes image, sticker, location, file, audio and video. Do not log message contents or reply tokens.
            log.info("Unsupported LINE message type={}, messageId={}, eventId={}",
                    event.message().getClass().getSimpleName(), event.message().id(), event.webhookEventId());
            messages.replyText(event.replyToken(), "暂不支持");
        }
    }
}
