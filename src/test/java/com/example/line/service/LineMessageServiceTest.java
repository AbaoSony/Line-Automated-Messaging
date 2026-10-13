package com.example.line.service;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.TextMessage;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class LineMessageServiceTest {
    @Test
    void sendsSdkTextReplyWithOriginalToken() throws Exception {
        var client = mock(MessagingApiClient.class);
        when(client.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
        new LineMessageService(client).replyText("reply-token", "你好");
        verify(client).replyMessage(argThat(request -> request.replyToken().equals("reply-token")
                && request.messages().size() == 1
                && request.messages().get(0) instanceof TextMessage text
                && text.text().equals("你好")));
    }
}
