package com.example.line.service;

import com.example.line.handler.*;
import com.linecorp.bot.webhook.model.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证异步 Webhook 的策略分发、重投递去重、Reply 年龄保护和失败释放。 */
class WebhookServiceTest {
    private InMemoryEventDeduplicator cache() {
        return new InMemoryEventDeduplicator(Duration.ofHours(24), 100, Clock.systemUTC());
    }

    private byte[] payload(String id, boolean redelivery, String details) {
        return ("""
                {"destination":"Ubot","events":[{"webhookEventId":"%s","timestamp":1,
                "mode":"active","deliveryContext":{"isRedelivery":%s},
                "source":{"type":"user","userId":"Utest"},%s}]}
                """.formatted(id, redelivery, details)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    // 场景：文本正常回复，相同 ID 重投递跳过，首次出现的重投递仍处理。
    void textIsRepliedAndRedeliveryIsDeduplicated() throws Exception {
        var messages = mock(LineMessageService.class);
        var service = new WebhookService(Runnable::run,
                new EventDispatcher(List.of(new MessageEventHandler(messages))), cache());
        String text = """
                "type":"message","replyToken":"reply","message":{"type":"text","id":"123","text":"你好"}
                """;
        service.process(payload("event1", false, text));
        service.process(payload("event1", true, text));
        service.process(payload("event2", true, text));
        verify(messages, times(2)).replyText("reply", "你好");
    }

    @Test
    // 场景：四种非文本消息都分发到兜底回复。
    void unsupportedImageStickerLocationAndFileAreReplied() throws Exception {
        var messages = mock(LineMessageService.class);
        var service = new WebhookService(Runnable::run,
                new EventDispatcher(List.of(new MessageEventHandler(messages))), cache());
        List<String> contents = List.of(
                """
                {"type":"image","id":"1","contentProvider":{"type":"line"}}
                """,
                """
                {"type":"sticker","id":"2","packageId":"1","stickerId":"1","stickerResourceType":"STATIC"}
                """,
                """
                {"type":"location","id":"3","latitude":25.0,"longitude":121.0}
                """,
                """
                {"type":"file","id":"4","fileName":"sample.txt","fileSize":10}
                """);
        for (int i = 0; i < contents.size(); i++) {
            service.process(payload("event" + i, false,
                    "\"type\":\"message\",\"replyToken\":\"reply\",\"message\":" + contents.get(i)));
        }
        verify(messages, times(4)).replyText("reply", "暂不支持");
    }

    @Test
    // 场景：关注、取消关注和 Postback 各自进入注册的事件策略。
    void followUnfollowAndPostbackUseRegisteredStrategies() throws Exception {
        var follow = mock(FollowEventHandler.class);
        var unfollow = mock(UnfollowEventHandler.class);
        var postback = mock(PostbackEventHandler.class);
        when(follow.eventType()).thenReturn(FollowEvent.class);
        when(unfollow.eventType()).thenReturn(UnfollowEvent.class);
        when(postback.eventType()).thenReturn(PostbackEvent.class);
        var service = new WebhookService(Runnable::run,
                new EventDispatcher(List.of(follow, unfollow, postback)), cache());
        service.process(payload("follow", false, "\"type\":\"follow\",\"replyToken\":\"r\""));
        service.process(payload("unfollow", false, "\"type\":\"unfollow\""));
        service.process(payload("postback", false, "\"type\":\"postback\",\"postback\":{\"data\":\"action=test\"}"));
        verify(follow).handle(argThat(e -> e.source().userId().equals("Utest")));
        verify(unfollow).handle(argThat(e -> e.source().userId().equals("Utest")));
        verify(postback).handle(argThat(e -> e.postback().data().equals("action=test")));
    }

    @Test
    // 场景：处理失败释放 ID，之后再次投递可以执行，成功后再次去重。
    void handlerFailureReleasesIdAndDoesNotBlockLaterEvents() throws Exception {
        var dispatcher = mock(EventDispatcher.class);
        doThrow(new IllegalStateException()).doNothing().when(dispatcher).dispatch(any());
        var service = new WebhookService(Runnable::run, dispatcher, cache());
        byte[] body = payload("retry", true, "\"type\":\"unfollow\"");
        service.process(body);
        service.process(body);
        service.process(body);
        verify(dispatcher, times(2)).dispatch(any());
    }

    @Test
    // 场景：耗时 Handler 在工作线程中执行，不阻塞请求提交。
    void slowHandlerRunsOnWorkerAndDoesNotBlockSubmit() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var dispatcher = mock(EventDispatcher.class);
        doAnswer(call -> {
            worker.set(Thread.currentThread());
            started.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { finished.countDown(); }
            return null;
        }).when(dispatcher).dispatch(any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            new WebhookService(executor, dispatcher, cache())
                    .submit(payload("async", false, "\"type\":\"unfollow\""));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get()).isNotSameAs(Thread.currentThread());
            assertThat(finished.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    // 场景：本次接收后的等待超过 15 秒时不发送 Reply。
    void queuedReplyOlderThan15SecondsIsNotSent() throws Exception {
        var messages = mock(LineMessageService.class);
        var service = new WebhookService(Runnable::run,
                new EventDispatcher(List.of(new MessageEventHandler(messages))), cache());
        service.process(payload("stale", false,
                "\"type\":\"message\",\"replyToken\":\"reply\",\"message\":{\"type\":\"text\",\"id\":\"1\",\"text\":\"hello\"}"),
                System.nanoTime() - TimeUnit.SECONDS.toNanos(16));
        verifyNoInteractions(messages);
    }

    @Test
    // 场景：入队被拒绝时不抢占幂等键，也不在请求线程执行业务。
    void rejectionDoesNotClaimAnIdOrProcessOnRequestThread() {
        var dedup = mock(EventDeduplicator.class);
        var dispatcher = mock(EventDispatcher.class);
        new WebhookService(task -> { throw new RejectedExecutionException(); }, dispatcher, dedup)
                .submit(payload("rejected", false, "\"type\":\"unfollow\""));
        verifyNoInteractions(dedup, dispatcher);
    }
}
