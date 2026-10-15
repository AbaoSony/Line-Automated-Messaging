package com.example.line.chat;

import com.example.line.common.persistence.AsyncPersistence;
import com.example.line.messaging.LineMessageService;
import com.example.line.webhook.handler.MessageEventHandler;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实 Handler 接入失败隔离后仍保持即时 echo，只有成功 Reply 才记录出站。 */
class ChatMessageServiceTest {
    @Test
    void inboundAndOutboundDatabaseFailuresDoNotPreventEcho() throws Exception {
        var repository = mock(ChatRepository.class);
        doThrow(new DataAccessResourceFailureException("fake-db-password"))
                .when(repository).insert(any(), any(), nullable(String.class), any(), any(), any(),any());
        var meters = new SimpleMeterRegistry();
        var chat = new ChatMessageService(repository, new AsyncPersistence(Runnable::run, meters));
        var messages = mock(LineMessageService.class);
        var event = event();
        new MessageEventHandler(messages, chat,mock(com.example.line.ai.AiChatService.class),Runnable::run).handle(event);
        verify(messages).replyText(event.replyToken(), "hello");
        verify(repository, times(2)).insert(any(), any(), nullable(String.class), any(), any(), any(),any());
        assertThat(meters.get("persist.failed").tag("operation", "user.message.received").counter().count()).isEqualTo(1);
        assertThat(meters.get("persist.failed").tag("operation", "line.reply.sent").counter().count()).isEqualTo(1);
    }

    @Test
    void failedReplyKeepsInboundRecordWithoutRecordingSuccessfulOutbound() throws Exception {
        var repository = mock(ChatRepository.class);
        var chat = new ChatMessageService(repository, new AsyncPersistence(Runnable::run, new SimpleMeterRegistry()));
        var messages = mock(LineMessageService.class);
        var event = event();
        doThrow(new IllegalStateException("Simulated send failure")).when(messages).replyText(any(), any());
        assertThatThrownBy(() -> new MessageEventHandler(messages, chat,mock(com.example.line.ai.AiChatService.class),Runnable::run).handle(event)).isInstanceOf(IllegalStateException.class);
        verify(repository).insert(eq(event.source().userId()), eq("INBOUND"), eq(event.message().id()), eq("TEXT"), any(), any(),any());
        verify(repository, never()).insert(any(), eq("OUTBOUND"), any(), any(), any(), any(),any());
    }

    @Test
    void missingStorageMessageIdDoesNotChangeExistingReplyBehavior() throws Exception {
        var repository = mock(ChatRepository.class);
        var meters = new SimpleMeterRegistry();
        var chat = new ChatMessageService(repository, new AsyncPersistence(Runnable::run, meters));
        var messages = mock(LineMessageService.class);
        var original = event();
        var withoutId = new MessageEvent(original.source(), original.timestamp(), original.mode(),
                original.webhookEventId(), original.deliveryContext(), original.replyToken(),
                new TextMessageContent.Builder(null, "hello", "fake-quote-token").build());
        new MessageEventHandler(messages, chat,mock(com.example.line.ai.AiChatService.class),Runnable::run).handle(withoutId);
        verify(messages).replyText(original.replyToken(), "hello");
        verify(repository, never()).insert(any(), eq("INBOUND"), any(), any(), any(), any(),any());
        verify(repository).insert(any(), eq("OUTBOUND"), isNull(), eq("TEXT"), any(), any(),any());
        assertThat(meters.get("persist.failed").counter().count()).isEqualTo(1);
    }

    private MessageEvent event() throws Exception {
        var mapper=ModelObjectMapper.createNewObjectMapper();
        var root=mapper.readTree(Files.readAllBytes(Path.of("docs/examples/webhook/webhook-hello.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("events").get(0).path("source"))
                .put("type","group").put("groupId","Caaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        return (MessageEvent)mapper.treeToValue(root,CallbackRequest.class).events().get(0);
    }
}
