package com.example.line.messaging;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.TextMessage;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.example.line.messaging.LineSendProperties;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.io.IOException;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;

import static org.mockito.Mockito.*;

/** 验证 Reply/Push 请求构造、单次消息限制和 Push 重试幂等。 */
class LineMessageServiceTest {
    @Test void deletionWaitsForInFlightPushAndRejectsNewPushUntilUnblocked() throws Exception {
        var tracker=new com.example.line.common.execution.UserWorkTracker();
        var client=mock(MessagingApiClient.class);
        var accepted=new CompletableFuture<com.linecorp.bot.client.base.Result<com.linecorp.bot.messaging.model.PushMessageResponse>>();
        var entered=new java.util.concurrent.CountDownLatch(1);
        when(client.pushMessage(any(),any())).thenAnswer(call -> {entered.countDown();return accepted;});
        var service=new LineMessageService(client,new LineApiCaller(new LineSendProperties(1,Duration.ofSeconds(1),Duration.ofMillis(1))),tracker);
        String user="U"+"b".repeat(32);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var sending=worker.submit(() -> service.pushText(user,"完整虚构推送"));
            assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try(var barrier=tracker.block(user)) {
                assertThatThrownBy(() -> barrier.await(Duration.ofMillis(1))).hasMessageContaining("timed out");
                assertThatThrownBy(() -> service.pushText(user,"删除期间不得发送")).isInstanceOfSatisfying(LineSendException.class,
                        failure -> assertThat(failure.getDeliveryOutcome()).isEqualTo(LineSendException.DeliveryOutcome.NOT_SENT));
                accepted.complete(null);sending.get(1,java.util.concurrent.TimeUnit.SECONDS);
                barrier.await(Duration.ofSeconds(1));
            }
            service.pushText(user,"解除屏障后新推送");verify(client,times(2)).pushMessage(any(),any());
        }finally{accepted.complete(null);worker.shutdownNow();}
    }
    @Test
    // 场景：文本 Reply 传递当前 Token 和原始文本。
    void sendsSdkTextReplyWithOriginalToken() throws Exception {
        var client = mock(MessagingApiClient.class);
        when(client.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
        service(client).replyText("reply-token", "你好");
        verify(client).replyMessage(argThat(request -> request.replyToken().equals("reply-token")
                && request.messages().size() == 1
                && request.messages().get(0) instanceof TextMessage text
                && text.text().equals("你好")));
    }

    private LineMessageService service(MessagingApiClient client) {
        return new LineMessageService(client, new LineApiCaller(
                new LineSendProperties(3, Duration.ofMillis(50), Duration.ofMillis(1)), millis -> {}));
    }

    @Test
    // 场景：自动重试复用 Key 和消息体，新的服务调用生成不同 Key。
    void pushRetriesReuseKeyAndPayloadWhileNewCallsUseNewKeys() {
        var client = mock(MessagingApiClient.class);
        when(client.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var service = service(client);
        var first = service.pushText("U" + "a".repeat(32), "hello");
        var second = service.pushText("U" + "a".repeat(32), "hello");
        var keys = ArgumentCaptor.forClass(UUID.class);
        var requests = ArgumentCaptor.forClass(com.linecorp.bot.messaging.model.PushMessageRequest.class);
        verify(client, times(3)).pushMessage(keys.capture(), requests.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(first.retryKey()).isEqualTo(keys.getAllValues().get(1));
        assertThat(second.retryKey()).isNotEqualTo(first.retryKey());
        assertThat(requests.getAllValues().get(0)).isSameAs(requests.getAllValues().get(1));
    }

    @Test
    // 场景：Reply 不重试，非法消息批次与用户 ID 在 SDK 调用前失败。
    void replyDoesNotRetryAndValidationPreventsInvalidBatches() {
        var client = mock(MessagingApiClient.class);
        when(client.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(new IOException()));
        var service = service(client);
        assertThatThrownBy(() -> service.replyText("reply", "hello")).isInstanceOf(LineSendException.class);
        verify(client, times(1)).replyMessage(any());
        clearInvocations(client);
        List<Message> six = new ArrayList<>(java.util.Collections.nCopies(6, LineMessages.text("hello")));
        for (List<Message> invalid : List.of(List.<Message>of(), six)) {
            assertThatThrownBy(() -> service.replyMessages("reply", invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.pushMessages("U" + "a".repeat(32), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.pushText("invalid-user", "hello")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    // 场景：合法的五条根消息能够通过封装层并提交 SDK。
    void fiveMessagesAreAccepted() {
        var client = mock(MessagingApiClient.class);
        when(client.pushMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        service(client).pushMessages("U" + "a".repeat(32), java.util.Collections.nCopies(5, LineMessages.text("hello")));
        verify(client).pushMessage(any(), argThat(request -> request.messages().size() == 5));
    }
}
