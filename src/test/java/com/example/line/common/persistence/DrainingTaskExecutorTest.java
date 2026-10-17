package com.example.line.common.persistence;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.logging.recording.RecordedEntry;
import com.example.line.common.logging.recording.RecorderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

/** 验证真实 Spring 停机先排空上游业务，再排空异步落库，最后刷完日志队列。 */
class DrainingTaskExecutorTest {
    @Test void shutdownTimeoutReportsFailureAndStillNotifiesSpringWithoutPretendingWorkerExited()throws Exception{
        var pool=executor(DrainingTaskExecutor.PERSISTENCE_PHASE);pool.setAwaitTerminationMillis(20);pool.initialize();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var notified=new java.util.concurrent.atomic.AtomicBoolean();
        pool.execute(() -> {entered.countDown();try{assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException failure){throw new AssertionError(failure);}});
        try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pool.stop(() -> notified.set(true))).isInstanceOf(IllegalStateException.class).hasMessageContaining("did not drain");
            assertThat(notified).isTrue();assertThat(pool.getThreadPoolExecutor().isTerminated()).isFalse();
        }finally{release.countDown();assertThat(pool.getThreadPoolExecutor().awaitTermination(1,TimeUnit.SECONDS)).isTrue();pool.destroy();}
    }
    @Test
    void contextShutdownFlushesEventsEmittedByBothProducerPools() throws Exception {
        var written = new CopyOnWriteArrayList<RecordedEntry>();
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("flywayInitializer", Object.class, Object::new);
        context.registerBean("persistenceExecutor", DrainingTaskExecutor.class, () -> executor(DrainingTaskExecutor.PERSISTENCE_PHASE));
        context.registerBean("webhookExecutor", DrainingTaskExecutor.class, () -> executor(DrainingTaskExecutor.WEBHOOK_PHASE));
        context.registerBean(EventRecorder.class, () -> new EventRecorder(written::addAll,
                new RecorderProperties(10, 2, Duration.ofMillis(1), 2, 250, 1), new SimpleMeterRegistry(), new MockEnvironment()));
        context.refresh();
        var webhook = context.getBean("webhookExecutor", DrainingTaskExecutor.class);
        var persistence = context.getBean("persistenceExecutor", DrainingTaskExecutor.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        webhook.execute(() -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            BusinessLog.write(LoggerFactory.getLogger(getClass()), Level.INFO, LogEvents.USER_FOLLOW, "Final webhook event", Map.of());
            persistence.execute(() -> BusinessLog.write(LoggerFactory.getLogger(getClass()), Level.INFO,
                    LogEvents.USER_UNFOLLOW, "Final persistence event", Map.of()));
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        context.close();
        assertThat(written).extracting(entry -> entry.data().get("event")).containsExactlyInAnyOrder("user.follow", "user.unfollow");
        assertThat(webhook.getThreadPoolExecutor().isTerminated()).isTrue();
        assertThat(persistence.getThreadPoolExecutor().isTerminated()).isTrue();
    }

    private DrainingTaskExecutor executor(int phase) {
        var executor = new DrainingTaskExecutor();
        executor.setPhase(phase);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
