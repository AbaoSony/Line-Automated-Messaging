package com.example.line.service;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LineMessageService {
    private final MessagingApiClient client;
    private final LineApiCaller caller;

    public LineMessageService(MessagingApiClient client, LineApiCaller caller) {
        this.client = client;
        this.caller = caller;
    }

    /**
     * Reply is free and does not consume the monthly message quota; prefer it to Push.
     * replyToken is single-use and short-lived. Use immediately in the event worker,
     * never persist for delayed work. Reply has no retry-key support and is not retried.
     */
    public void replyText(String replyToken, String text) {
        replyMessages(replyToken, List.of(LineMessages.text(text)));
    }

    public void replyMessages(String replyToken, List<Message> messages) {
        if (replyToken == null || replyToken.isBlank()) {
            throw new IllegalArgumentException("Missing reply token");
        }
        var request = new ReplyMessageRequest.Builder(replyToken, validateMessages(messages)).build();
        caller.execute("reply", null, () -> client.replyMessage(request));
    }

    /** Push consumes quota. All automatic retries reuse the original key and payload. */
    public PushReceipt pushText(String userId, String text) {
        return pushMessages(userId, List.of(LineMessages.text(text)));
    }

    public PushReceipt pushMessages(String userId, List<Message> messages) {
        if (userId == null || !userId.matches("U[0-9a-f]{32}")) {
            throw new IllegalArgumentException("userId must be U followed by 32 lowercase hexadecimal characters");
        }
        var request = new PushMessageRequest.Builder(userId, validateMessages(messages)).build();
        UUID retryKey = UUID.randomUUID();
        boolean alreadyAccepted = caller.execute("push", retryKey, () -> client.pushMessage(retryKey, request));
        return new PushReceipt(retryKey, alreadyAccepted);
    }

    private List<Message> validateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty() || messages.size() > 5
                || messages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("A LINE request must contain 1..5 non-null messages");
        }
        return List.copyOf(messages);
    }

    public record PushReceipt(UUID retryKey, boolean alreadyAccepted) {}
}
