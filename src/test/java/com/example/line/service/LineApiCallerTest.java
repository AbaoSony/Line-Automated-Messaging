package com.example.line.service;

import com.example.line.config.LineSendProperties;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LineApiCallerTest {
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
    void acceptedConflictIsSuccessWithoutFurtherRetry() {
        assertThat(caller(new ArrayList<>()).execute("push", UUID.randomUUID(),
                () -> CompletableFuture.failedFuture(httpFailure(409, "accepted-request")))).isTrue();
    }
    @Test
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
