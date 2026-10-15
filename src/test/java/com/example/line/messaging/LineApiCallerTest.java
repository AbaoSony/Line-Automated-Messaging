package com.example.line.messaging;

import com.example.line.messaging.LineSendProperties;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证 SDK 错误分类、有限指数退避、超时取消与线程中断处理。 */
class LineApiCallerTest {
    @Test void sharedDeadlineIsRecheckedAfterStartingSdkRequest() {
        var clock=new java.util.concurrent.atomic.AtomicLong(0);
        var deadline=com.example.line.common.execution.Deadline.from(0,Duration.ofMillis(5),clock::get);
        var pending=new CompletableFuture<>();
        assertThatThrownBy(() -> caller(new ArrayList<>()).execute("reply",null,() -> {
            clock.set(Duration.ofMillis(6).toNanos());return pending;
        },deadline)).isInstanceOf(LineSendException.class);
        assertThat(pending.isCancelled()).isTrue();
    }
    private AbstractLineClientException httpFailure(int code, String acceptedId) {
        var error = mock(AbstractLineClientException.class);
        when(error.getCode()).thenReturn(code);
        when(error.getHeader("x-line-accepted-request-id")).thenReturn(acceptedId);
        return error;
    }
    private LineApiCaller caller(List<Long> delays) {
        return new LineApiCaller(new LineSendProperties(3, Duration.ofMillis(10), Duration.ofMillis(200)), delays::add);
    }
    @Test
    // 场景：持续 5xx 最多尝试配置次数，并按二倍间隔退避。
    void serverErrorsHaveFiniteExponentialBackoff() {
        var delays = new ArrayList<Long>();
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> caller(delays).execute("push", UUID.randomUUID(), () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(httpFailure(503, null));
        })).isInstanceOfSatisfying(LineSendException.class, e -> {
            assertThat(e.getReason()).isEqualTo(LineSendException.Reason.TRANSIENT_FAILURE);
            assertThat(e.getUpstreamStatus()).isEqualTo(503);
        });
        assertThat(calls.get()).isEqualTo(3);
        assertThat(delays).containsExactly(200L, 400L);
    }
    @Test
    // 场景：4xx 含 429 和普通 409 立即失败，不执行退避重试。
    void clientErrorsIncluding429AndOrdinary409AreNotRetried() {
        for (int code : List.of(400, 401, 403, 409, 429)) {
            var calls = new AtomicInteger();
            var delays = new ArrayList<Long>();
            assertThatThrownBy(() -> caller(delays).execute("push", UUID.randomUUID(), () -> {
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(httpFailure(code, null));
            })).isInstanceOfSatisfying(LineSendException.class,
                    e -> assertThat(e.getReason()).isEqualTo(LineSendException.Reason.CLIENT_ERROR));
            assertThat(calls.get()).isEqualTo(1);
            assertThat(delays).isEmpty();
        }
    }
    @Test
    // 场景：409 带已接受请求标识时视为成功且停止重试。
    void acceptedConflictIsSuccessWithoutFurtherRetry() {
        assertThat(caller(new ArrayList<>()).execute("push", UUID.randomUUID(),
                () -> CompletableFuture.failedFuture(httpFailure(409, "accepted-request")))).isTrue();
    }
    @Test
    // 场景：网络故障可以重试，首次成功后不再调用 SDK。
    void networkErrorsRetryAndSuccessStopsRetrying() {
        var calls = new AtomicInteger();
        var delays = new ArrayList<Long>();
        assertThat(caller(delays).execute("push", UUID.randomUUID(), () ->
                calls.incrementAndGet() == 1 ? CompletableFuture.failedFuture(new IOException())
                        : CompletableFuture.completedFuture(null))).isFalse();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(delays).containsExactly(200L);
    }
    @Test
    // 场景：连续超时受次数限制，每个尚未完成的 Future 都尝试取消。
    void timeoutsAreBoundedAndCancelPendingFutures() {
        var futures = new ArrayList<CompletableFuture<?>>();
        assertThatThrownBy(() -> caller(new ArrayList<>()).execute("push", UUID.randomUUID(), () -> {
            var pending = new CompletableFuture<>();
            futures.add(pending);
            return pending;
        })).isInstanceOf(LineSendException.class);
        assertThat(futures).hasSize(3).allMatch(CompletableFuture::isCancelled);
    }
    @Test
    // 场景：退避被中断后停止重试，并恢复线程中断标记。
    void interruptionRestoresFlagWithoutRetrying() {
        var caller = new LineApiCaller(new LineSendProperties(3, Duration.ofMillis(10), Duration.ofMillis(1)),
                millis -> { throw new InterruptedException(); });
        try {
            assertThatThrownBy(() -> caller.execute("push", UUID.randomUUID(),
                    () -> CompletableFuture.failedFuture(new IOException())))
                    .isInstanceOfSatisfying(LineSendException.class,
                            e -> assertThat(e.getReason()).isEqualTo(LineSendException.Reason.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
