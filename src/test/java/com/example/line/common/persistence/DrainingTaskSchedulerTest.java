package com.example.line.common.persistence;

import com.example.line.common.logging.*;
import com.example.line.common.logging.recording.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** 定时清理实际退出前不得关闭写入及日志；没有访问真实数据库。 */
class DrainingTaskSchedulerTest {
    @Test void runningCleanupTimeoutIsExplicitAndDoesNotClaimActualExit()throws Exception{
        var scheduler=new DrainingTaskScheduler();scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationMillis(20);scheduler.initialize();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var notified=new java.util.concurrent.atomic.AtomicBoolean();
        scheduler.schedule(() -> {entered.countDown();try{assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException failure){throw new AssertionError(failure);}},Instant.now());
        try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> scheduler.stop(() -> notified.set(true))).isInstanceOf(IllegalStateException.class).hasMessageContaining("did not drain");
            assertThat(notified).isTrue();assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isFalse();
        }finally{release.countDown();assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(1,TimeUnit.SECONDS)).isTrue();scheduler.destroy();}
    }
    @Test void runningRetentionFinishesBeforeBusinessPoolAndRecorderClose()throws Exception{
        var context=new AnnotationConfigApplicationContext();var written=new CopyOnWriteArrayList<RecordedEntry>();
        context.registerBean("flywayInitializer",Object.class,Object::new);
        context.registerBean("persistenceExecutor",DrainingTaskExecutor.class,() -> {
            var pool=new DrainingTaskExecutor();pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(2);
            pool.setPhase(DrainingTaskExecutor.PERSISTENCE_PHASE);pool.setWaitForTasksToCompleteOnShutdown(true);pool.setAwaitTerminationSeconds(2);return pool;
        });
        context.registerBean("taskScheduler",DrainingTaskScheduler.class,() -> {
            var scheduler=new DrainingTaskScheduler();scheduler.setPhase(DrainingTaskExecutor.WEBHOOK_PHASE+2);
            scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(2);return scheduler;
        });
        context.registerBean(EventRecorder.class,() -> new EventRecorder(written::addAll,
                new RecorderProperties(10,2,Duration.ofMillis(10),2,250,1),new SimpleMeterRegistry(),new MockEnvironment()));
        context.refresh();var scheduler=context.getBean(DrainingTaskScheduler.class);var pool=context.getBean(DrainingTaskExecutor.class);
        var recorder=context.getBean(EventRecorder.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        scheduler.schedule(() -> {entered.countDown();try{assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException failure){throw new AssertionError(failure);}
            assertThat(recorder.isRunning()).isTrue();assertThat(pool.getThreadPoolExecutor().isShutdown()).isFalse();
            pool.execute(() -> BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.CHAT_RETENTION_CLEANED,
                    "Final retention operation",BusinessLog.fields("outcome","SUCCESS")));
        },Instant.now());
        try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();var closed=CompletableFuture.runAsync(context::close);
            assertThatThrownBy(() -> closed.get(30,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();closed.get(3,TimeUnit.SECONDS);assertThat(pool.getThreadPoolExecutor().isTerminated()).isTrue();
            assertThat(written).extracting(entry -> entry.data().get("event")).containsExactly(LogEvents.CHAT_RETENTION_CLEANED);
        }finally{release.countDown();context.close();}
    }
}
