package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.common.execution.Deadline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.context.properties.bind.Binder;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 用同步工具验证同用户 FIFO、公平并发、有界拒绝、等待过期和删除取消，无长时间 sleep。 */
class SessionSchedulerTest {
    private AiProperties settings(int queue,int user,Duration wait){
        var env=new MockEnvironment().withProperty("line.ai.workers","2").withProperty("line.ai.queue-capacity",Integer.toString(queue))
                .withProperty("line.ai.user-queue-capacity",Integer.toString(user)).withProperty("line.ai.queue-wait",wait.toString());
        return Binder.get(env).bindOrCreate("line.ai",AiProperties.class);
    }
    private Deadline deadline(){return Deadline.current(Duration.ofSeconds(15));}
    private static void waitFor(CountDownLatch latch){try{assertThat(latch.await(3,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
    @Test void sameUserRunsInSubmissionOrderWhileOtherUserCanRun(){
        var scheduler=new SessionScheduler(settings(10,3,Duration.ofSeconds(2)),new SimpleMeterRegistry());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var other=new CountDownLatch(1);
        var order=new CopyOnWriteArrayList<Integer>();var cancelled=new AtomicInteger();
        try{
            scheduler.submit("one",deadline(),() -> {entered.countDown();waitFor(release);order.add(1);},() -> fail("unexpected rejection"),cancelled::incrementAndGet);
            waitFor(entered);
            scheduler.submit("one",deadline(),() -> order.add(2),() -> fail("unexpected rejection"),cancelled::incrementAndGet);
            scheduler.submit("one",deadline(),() -> order.add(3),() -> fail("unexpected rejection"),cancelled::incrementAndGet);
            scheduler.submit("two",deadline(),other::countDown,() -> fail("unexpected rejection"),cancelled::incrementAndGet);
            waitFor(other);assertThat(order).isEmpty();release.countDown();
            await().atMost(Duration.ofSeconds(3)).until(scheduler::isIdle);
            assertThat(order).containsExactly(1,2,3);assertThat(cancelled.get()).isEqualTo(4);
        }finally{release.countDown();scheduler.stop();}
    }
    @Test void userQueueRejectsWithoutExecutingModelActionAndDeletionCancelsQueuedWork() throws Exception {
        var scheduler=new SessionScheduler(settings(2,1,Duration.ofSeconds(2)),new SimpleMeterRegistry());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var rejected=new CountDownLatch(1);var closed=new AtomicInteger();
        try{
            scheduler.submit("one",deadline(),() -> {entered.countDown();waitActualExit(release);},() -> fail("unexpected"),closed::incrementAndGet);waitFor(entered);
            var queued=scheduler.submit("one",deadline(),() -> fail("deleted queued task executed"),() -> fail("deleted task sent hint"),closed::incrementAndGet);
            var overflow=scheduler.submit("one",deadline(),() -> fail("overflow executed"),rejected::countDown,closed::incrementAndGet);
            waitFor(rejected);overflow.toCompletableFuture().get(3,TimeUnit.SECONDS);assertThat(overflow.toCompletableFuture().isDone()).isTrue();scheduler.cancel("one");
            assertThat(queued.toCompletableFuture().isDone()).isTrue();release.countDown();
            await().atMost(Duration.ofSeconds(3)).until(scheduler::isIdle);assertThat(closed.get()).isEqualTo(3);
        }finally{release.countDown();scheduler.stop();}
    }
    @Test void cancelledRunningLegacyTaskStaysCancelledAfterLateModelExitAndRetainsActualCompletion()throws Exception{
        var scheduler=new SessionScheduler(settings(2,1,Duration.ofSeconds(2)),new SimpleMeterRegistry());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            var running=scheduler.submit("one",deadline(),() -> {entered.countDown();waitActualExit(release);cancelled.set(scheduler.currentTaskCancelled());},
                    () -> fail("unexpected rejection"),() -> {});waitFor(entered);scheduler.cancel("one");
            assertThat(running.toCompletableFuture()).isNotDone();assertThat(scheduler.isIdle()).isFalse();
            release.countDown();running.toCompletableFuture().get(1,TimeUnit.SECONDS);assertThat(cancelled).isTrue();
        }finally{release.countDown();scheduler.stop();}
    }
    private static void waitActualExit(CountDownLatch release){boolean interrupted=false;
        try{while(true)try{assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return;}catch(InterruptedException failure){interrupted=true;}}
        finally{if(interrupted)Thread.currentThread().interrupt();}}
    @Test void queuedDeadlineExpiresWithoutOccupyingWorkerOrInvokingModel(){
        var scheduler=new SessionScheduler(settings(10,3,Duration.ofMillis(30)),new SimpleMeterRegistry());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var rejected=new CountDownLatch(1);
        try{
            scheduler.submit("one",deadline(),() -> {entered.countDown();waitFor(release);},() -> fail("unexpected"),() -> {});waitFor(entered);
            scheduler.submit("one",deadline(),() -> fail("expired model action executed"),rejected::countDown,() -> {});
            waitFor(rejected);assertThat(release.getCount()).isEqualTo(1);
        }finally{release.countDown();scheduler.stop();}
    }
}
