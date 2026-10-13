package com.example.line.service;

import com.example.line.config.WebhookProperties;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class LineContentService {
    private final MessagingApiBlobClient client;
    private final long maxBytes;

    public LineContentService(MessagingApiBlobClient client, WebhookProperties properties) {
        this.client = client;
        this.maxBytes = properties.contentMaxBytes();
    }

    /** Call from a worker for image/file message IDs. Caller owns the destination stream. */
    public long downloadTo(String messageId, OutputStream destination) throws Exception {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("Missing messageId");
        }
        var content = client.getMessageContent(messageId).get(20, TimeUnit.SECONDS).body();
        try (var input = content.byteStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException("LINE content exceeds configured byte limit");
                }
                destination.write(buffer, 0, count);
            }
            return total;
        }
    }
}
