package com.example.line.common.persistence;

import com.example.line.common.logging.LogEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;

/** 验证异步落库的故障隔离、拒绝计数、MDC 传播和凭证不进入失败日志。 */
@ExtendWith(OutputCaptureExtension.class)
class AsyncPersistenceTest {
    @Test
    void databaseFailureIsCountedWithoutPropagatingOrLoggingCredential(CapturedOutput output) {
        var meters = new SimpleMeterRegistry();
        var persistence = new AsyncPersistence(Runnable::run, meters);
        persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> {
            throw new IllegalStateException("fake-database-password");
        });
        assertThat(meters.get("persist.failed").tag("operation", LogEvents.USER_FOLLOW).counter().count()).isEqualTo(1);
        assertThat(output).contains("persist.failed", "Ufictional-original-user", "trace_id")
                .doesNotContain("fake-database-password");
    }

    @Test
    void rejectedTaskIsDroppedAndCountedWithoutCallerExecution() {
        var meters = new SimpleMeterRegistry();
        var persistence = new AsyncPersistence(task -> { throw new RejectedExecutionException(); }, meters);
        var executed = new java.util.concurrent.atomic.AtomicBoolean();
        persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> executed.set(true));
        assertThat(executed).isFalse();
        assertThat(meters.get("persistence.rejected").counter().count()).isEqualTo(1);
        assertThat(meters.get("persist.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void fullRealBoundedExecutorRejectsWithoutRunningOnCallerThread() throws Exception {
        var executor = new PersistenceConfig().persistenceExecutor(new PersistenceProperties(1, 1, Duration.ofSeconds(5), 10));
        executor.initialize();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var ranRejected = new java.util.concurrent.atomic.AtomicBoolean();
        var meters = new SimpleMeterRegistry();
        var persistence = new AsyncPersistence(executor, meters);
        try {
            persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> {
                started.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> {});
            persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> ranRejected.set(true));
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            assertThat(ranRejected).isFalse();
            assertThat(meters.get("persistence.rejected").counter().count()).isEqualTo(1);
        } finally {
            finish.countDown();
            executor.shutdown();
        }
    }

    @Test
    void slowDatabaseDoesNotBlockSubmitAndMdcIsCopiedAndCleared() throws Exception {
        var executor = new PersistenceConfig().persistenceExecutor(new PersistenceProperties(1, 2, Duration.ofSeconds(5), 10));
        executor.initialize();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var trace = new AtomicReference<String>();
        var nextTrace = new AtomicReference<String>();
        var nextDone = new CountDownLatch(1);
        try {
            var persistence = new AsyncPersistence(executor, new SimpleMeterRegistry());
            MDC.put("trace_id", "fictional-trace");
            persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> {
                trace.set(MDC.get("trace_id"));
                started.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(trace.get()).isEqualTo("fictional-trace");
            assertThat(finish.getCount()).isEqualTo(1);
            MDC.clear();
            persistence.submit(LogEvents.USER_FOLLOW, "Ufictional-original-user", () -> {
                nextTrace.set(MDC.get("trace_id"));
                nextDone.countDown();
            });
            finish.countDown();
            assertThat(nextDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(nextTrace.get()).isNull();
        } finally {
            MDC.clear();
            finish.countDown();
            executor.shutdown();
        }
    }

    @Test
    void databaseConfigurationFailsWithoutCredentialsAndDoesNotExposeValues() {
        assertThatThrownBy(() -> new DatabaseProperties("", "", "", 2, 3000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DATABASE_URL", "DATABASE_PASSWORD");
        var config = new DatabaseProperties("fictional-url", "fictional-account", "fictional-password", 2, 3000);
        assertThat(config.toString()).doesNotContain("fictional-url", "fictional-account", "fictional-password");
        assertThatThrownBy(() -> new DatabaseProperties("", "fictional-account", "fictional-password", 2, 3000))
                .hasMessageNotContaining("fictional-account").hasMessageNotContaining("fictional-password");
    }
}
