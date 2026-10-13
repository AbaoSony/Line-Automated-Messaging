package com.example.line.service;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class LineMessageService {
    private final MessagingApiClient client;

    public LineMessageService(MessagingApiClient client) {
        this.client = client;
    }

    public void replyText(String replyToken, String text) throws Exception {
        if (replyToken == null || replyToken.isBlank()) {
            throw new IllegalArgumentException("Missing reply token");
        }
        client.replyMessage(new ReplyMessageRequest.Builder(replyToken, List.of(new TextMessage(text))).build())
                .get(20, TimeUnit.SECONDS);
    }
}
