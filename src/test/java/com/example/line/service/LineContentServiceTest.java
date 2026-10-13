package com.example.line.service;

import com.example.line.config.WebhookProperties;
import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LineContentServiceTest {
    @Test
    void downloadsByMessageIdAndClosesUpstream() throws Exception {
        var client = mock(MessagingApiBlobClient.class);
        var content = mock(BlobContent.class);
        var input = spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(content.byteStream()).thenReturn(input);
        when(client.getMessageContent("123")).thenReturn(
                CompletableFuture.completedFuture(new Result<>(null, null, content)));
        var output = new ByteArrayOutputStream();
        assertThat(service(client, 10).downloadTo("123", output)).isEqualTo(3);
        assertThat(output.toByteArray()).containsExactly(1, 2, 3);
        verify(client).getMessageContent("123");
        verify(input).close();
    }

    @Test
    void oversizedContentFailsAndClosesUpstream() throws Exception {
        var client = mock(MessagingApiBlobClient.class);
        var content = mock(BlobContent.class);
        var input = spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(content.byteStream()).thenReturn(input);
        when(client.getMessageContent("123")).thenReturn(
                CompletableFuture.completedFuture(new Result<>(null, null, content)));
        assertThatThrownBy(() -> service(client, 2).downloadTo("123", new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        verify(input).close();
    }

    private LineContentService service(MessagingApiBlobClient client, long max) {
        return new LineContentService(client, new WebhookProperties(1, 1, Duration.ofHours(24), 10, max));
    }
}
