package com.example.line.user;

import com.example.line.common.execution.*;
import com.example.line.common.persistence.PersistenceProperties;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 用受控执行器和真实截止验证生命周期 FIFO、容量、删除和迟到数据库结果，不调用外部服务。 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class UserLifecycleQueueTest {
    private PersistenceProperties settings(int capacity){return new PersistenceProperties(2,capacity,Duration.ofSeconds(2),100);}
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        @Override public void execute(Runnable task){tasks.addLast(task);}
        void next(){tasks.removeFirst().run();}
    }
    @Test void sameUserIsFifoWhileAnotherUserCanProceedAndModelCompletionDoesNotHoldAdmission(){
        var pool=new Manual();var queue=new UserLifecycleQueue(pool,settings(4),new UserWorkTracker());
        var order=new ArrayList<String>();var model=new CompletableFuture<Void>();
        try {
            queue.submit("A","user.follow",() -> {order.add("follow");return true;});
            queue.submit("A","ai.turn.admitted",() -> {order.add("admit");return model;});
            queue.submit("A","user.unfollow",() -> {order.add("unfollow");return true;});
            queue.submit("B","user.follow",() -> {order.add("other");return true;});
            assertThat(pool.tasks).hasSize(2);pool.next();pool.next();pool.next();pool.next();
            assertThat(order).containsExactly("follow","other","admit","unfollow");assertThat(model).isNotDone();assertThat(queue.isIdle()).isTrue();
        }finally{queue.stop();}
    }
    @Test void capacityCountsWaitingLanesAndRejectsWithoutExecutingOrLeakingDeletionLease(){
        var pool=new Manual();var tracker=new UserWorkTracker();var queue=new UserLifecycleQueue(pool,settings(2),tracker);
        try {
            queue.submit("A","user.follow",() -> true);queue.submit("A","user.unfollow",() -> true);
            var rejected=queue.submit("B","user.follow",() -> {throw new AssertionError("overflow executed");});
            assertThat(rejected.toCompletableFuture()).isCompletedExceptionally();
            try(var barrier=tracker.block("B")){barrier.await(Duration.ofMillis(10));}
            pool.next();pool.next();assertThat(queue.isIdle()).isTrue();
        }finally{queue.stop();}
    }
    @Test void deletionRejectsQueuedStatusWorkAndCannotPurgeBeforeActualTaskExit(){
        var pool=new Manual();var tracker=new UserWorkTracker();var queue=new UserLifecycleQueue(pool,settings(2),tracker);
        try {
            var result=queue.submit("A","user.follow",() -> {throw new AssertionError("deleted work executed");});
            try(var barrier=tracker.block("A")){
                assertThatThrownBy(() -> queue.purge("A")).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> barrier.await(Duration.ofMillis(10))).isInstanceOf(IllegalStateException.class);
                pool.next();barrier.await(Duration.ofMillis(10));queue.purge("A");
                assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            }
        }finally{queue.stop();}
    }
    @Test void expiredAdmissionDropsHeldCallbackButStillCountsActualBlockedDatabaseRead()throws Exception{
        var pool=Executors.newSingleThreadExecutor();var tracker=new UserWorkTracker();var queue=new UserLifecycleQueue(pool,settings(2),tracker);
        var repository=mock(UserRepository.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var called=new AtomicInteger();
        when(repository.active("A")).thenAnswer(call -> {entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return true;});
        var users=new UserService(repository,queue,List.of());
        try {
            var result=users.admitActive("A",Deadline.current(Duration.ofMillis(150)),() -> {called.incrementAndGet();return CompletableFuture.completedStage(null);});
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();result.toCompletableFuture().get(1,TimeUnit.SECONDS);
            assertThat(queue.isIdle()).isFalse();
            try(var barrier=tracker.block("A")){
                assertThatThrownBy(() -> barrier.await(Duration.ofMillis(10))).isInstanceOf(IllegalStateException.class);
                release.countDown();barrier.await(Duration.ofSeconds(1));
            }
            assertThat(called).hasValue(0);assertThat(queue.isIdle()).isTrue();
        }finally{release.countDown();queue.stop();pool.shutdownNow();}
    }
    @Test void lifecycleFailureIsSafeAndDoesNotCancelOrRequestWebhookRetry(org.springframework.boot.test.system.CapturedOutput output){
        var queue=new UserLifecycleQueue(Runnable::run,settings(2),new UserWorkTracker());var repository=mock(UserRepository.class);
        var listener=mock(UserLifecycleListener.class);var users=new UserService(repository,queue,List.of(listener));
        var order=LifecycleOrder.current(Instant.now(),"fictional-lifecycle",false);
        when(repository.updateStatus("A",false,order)).thenThrow(new IllegalStateException("fictional-private-password"));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(UserLifecycleQueue.class);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();captured.start();logger.addAppender(captured);
        try{assertThat(users.recordStatus("A",false,order).toCompletableFuture().join()).isFalse();verifyNoInteractions(listener);
            assertThat(output).doesNotContain("fictional-private-password");assertThat(captured.list).hasSize(1);
            String json=new com.example.line.common.logging.JsonLogFormatter(new org.springframework.mock.env.MockEnvironment()).format(captured.list.get(0));
            assertThat(json).doesNotContain("fictional-private-password").contains("\"user_id\":\"A\"","\"trace_id\"","persist.failed");}
        finally{logger.detachAppender(captured);captured.stop();queue.stop();}
    }
    @Test void shutdownClosesNewAdmissionAndWaitsUntilShortActionsActuallyExit()throws Exception{
        var pool=Executors.newSingleThreadExecutor();var queue=new UserLifecycleQueue(pool,settings(2),new UserWorkTracker());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            queue.submit("A","user.follow",() -> {entered.countDown();try{assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){throw new AssertionError(failure);}return true;});
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();var stopped=CompletableFuture.runAsync(queue::stop);
            await().atMost(Duration.ofSeconds(1)).until(() -> !queue.isRunning());assertThat(stopped).isNotDone();
            assertThat(queue.submit("B","user.follow",() -> true).toCompletableFuture()).isCompletedExceptionally();
            release.countDown();stopped.get(1,TimeUnit.SECONDS);assertThat(queue.isIdle()).isTrue();
        }finally{release.countDown();queue.stop();pool.shutdownNow();}
    }
}
