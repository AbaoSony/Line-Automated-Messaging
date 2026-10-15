package com.example.line.webhook.handler;

import com.example.line.messaging.LineSendProperties;
import com.example.line.messaging.*;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用 mock SDK 验证消息 Handler 的文本回显、兜底回复、standby 和失败传播。 */
class MessageEventHandlerTest {
    private final MessagingApiClient client = mock(MessagingApiClient.class);
    private final MessageEventHandler handler = new MessageEventHandler(new LineMessageService(client,
            new LineApiCaller(new LineSendProperties(3, Duration.ofSeconds(1), Duration.ofMillis(1)))), mock(com.example.line.chat.ChatMessageService.class));

    // 场景：中文文本与 hello 都使用当前事件的 Reply Token 原样回显。
    @ParameterizedTest
    @ValueSource(strings = {"text", "hello"})
    void textEchoUsesOriginalTokenAndContent(String sample) throws Exception {
        MessageEvent event = event(sample);
        when(client.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
        handler.handle(event);
        verify(client).replyMessage(argThat(request ->
                request.replyToken().equals(event.replyToken()) && request.messages().size() == 1
                        && request.messages().get(0) instanceof TextMessage text
                        && text.text().equals(((TextMessageContent) event.message()).text())));
        verify(client, never()).pushMessage(any(), any());
    }

    // 场景：图片、贴图、位置和文件均通过 SDK 回复“暂不支持”，不改为 Push。
    @ParameterizedTest
    @ValueSource(strings = {"image", "sticker", "location", "file"})
    void unsupportedContentRepliesWithFallbackThroughSdk(String sample) throws Exception {
        MessageEvent event = event(sample);
        when(client.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
        handler.handle(event);
        verify(client).replyMessage(argThat(request -> request.replyToken().equals(event.replyToken())
                && request.messages().get(0) instanceof TextMessage text && text.text().equals("暂不支持")));
        verify(client, never()).pushMessage(any(), any());
    }

    // 场景：standby 模式不调用任何发送 API。
    @Test
    void standbyDoesNotSend() throws Exception {
        MessageEvent active = event("hello");
        handler.handle(new MessageEvent(active.source(), active.timestamp(), EventMode.STANDBY,
                active.webhookEventId(), active.deliveryContext(), active.replyToken(), active.message()));
        verifyNoInteractions(client);
    }

    // 场景：缺少 message 内容时在调用 SDK 前失败。
    @Test
    void missingContentIsRejectedBeforeSdkCall() throws Exception {
        MessageEvent event = event("hello");
        assertThatThrownBy(() -> handler.handle(new MessageEvent(event.source(), event.timestamp(), event.mode(),
                event.webhookEventId(), event.deliveryContext(), event.replyToken(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    // 场景：Reply 失败只传播安全异常，不重试且不转成消耗额度的 Push。
    @Test
    void failedReplyPropagatesSanitizedErrorAndDoesNotFallbackToPush() throws Exception {
        when(client.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(new IOException("private-message")));
        assertThatThrownBy(() -> handler.handle(event("hello"))).isInstanceOf(LineSendException.class)
                .hasMessageNotContaining("private-message");
        verify(client, times(1)).replyMessage(any());
        verify(client, never()).pushMessage(any(), any());
    }

    private MessageEvent event(String sample) throws IOException {
        return (MessageEvent) ModelObjectMapper.createNewObjectMapper().readValue(
                Files.readAllBytes(Path.of("docs/examples/webhook/webhook-" + sample + ".json")), CallbackRequest.class).events().get(0);
    }
}
