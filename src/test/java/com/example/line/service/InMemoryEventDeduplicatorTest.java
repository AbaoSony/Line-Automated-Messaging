package com.example.line.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InMemoryEventDeduplicatorTest {
    @Test
    void completedEntriesExpireButInFlightEntriesRemainReserved() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var cache = new InMemoryEventDeduplicator(Duration.ofMinutes(1), 2, clock);
        assertThat(cache.tryAcquire("pending")).isTrue();
        assertThat(cache.tryAcquire("completed")).isTrue();
        cache.complete("completed");
        when(clock.instant()).thenReturn(start.plusSeconds(61));
        assertThat(cache.tryAcquire("pending")).isFalse();
        assertThat(cache.tryAcquire("completed")).isTrue();
        assertThatThrownBy(() -> cache.tryAcquire("overflow")).isInstanceOf(IllegalStateException.class);
        cache.release("completed");
        assertThat(cache.tryAcquire("overflow")).isTrue();
    }

    @Test
    void concurrentDeliveryOnlyAcquiresOnce() throws Exception {
        var cache = new InMemoryEventDeduplicator(Duration.ofHours(24), 10,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var executor = Executors.newFixedThreadPool(8);
        var acquired = new AtomicInteger();
        try {
            for (int i = 0; i < 50; i++) {
                executor.submit(() -> { if (cache.tryAcquire("same")) acquired.incrementAndGet(); });
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(acquired.get()).isEqualTo(1);
    }
}
