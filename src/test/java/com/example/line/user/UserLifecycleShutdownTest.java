package com.example.line.user;

import com.example.line.ai.AiProperties;
import com.example.line.ai.aggregation.*;
import com.example.line.ai.coordination.*;
import com.example.line.ai.decision.*;
import com.example.line.common.execution.*;
import com.example.line.common.logging.*;
import com.example.line.common.logging.recording.*;
import com.example.line.common.persistence.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 用真实 Spring 生命周期验证发送收尾、实际传输租约、持久化和最终日志按阶段关闭，无真实网络。 */
class UserLifecycleShutdownTest {
    @Test void springShutdownDrainsSendingAndActualTransportBeforePersistenceAndFinalLogFlush()throws Exception{
        var context=new AnnotationConfigApplicationContext();var written=new CopyOnWriteArrayList<RecordedEntry>();
        var settings=Binder.get(new MockEnvironment()).bindOrCreate("line.ai",AiProperties.class);
        var meters=new SimpleMeterRegistry();
        context.registerBean("flywayInitializer",Object.class,Object::new);
        context.registerBean(PersistenceProperties.class,() -> new PersistenceProperties(2,10,Duration.ofSeconds(3),100));
        context.registerBean("persistenceExecutor",DrainingTaskExecutor.class,() -> executor(DrainingTaskExecutor.PERSISTENCE_PHASE));
        context.register(UserLifecycleQueue.class);context.scan("com.example.line.common.execution");
        context.registerBean(SessionScheduler.class,() -> new SessionScheduler(settings,meters));
        context.registerBean(EventRecorder.class,() -> new EventRecorder(written::addAll,
                new RecorderProperties(100,10,Duration.ofMillis(10),2,250,1),meters,new MockEnvironment(),context.getBean(UserWorkTracker.class)));
        context.refresh();var scheduler=context.getBean(SessionScheduler.class);var tracker=context.getBean(UserWorkTracker.class);
        var persistence=context.getBean("persistenceExecutor",DrainingTaskExecutor.class);var recorder=context.getBean(EventRecorder.class);
        var sent=new CountDownLatch(1);var release=new CountDownLatch(1);var transport=new AtomicReference<UserWorkTracker.Lease>();
        var work=tracker.register("Ufictional-shutdown");var owner=new AtomicReference<TurnCoordinator>();
        var turns=scheduler.enableTurns(Binder.get(new MockEnvironment()).bindOrCreate("line.ai.aggregation",AggregationProperties.class),
                Binder.get(new MockEnvironment().withProperty("line.ai.decision.mode","RULE")).bindOrCreate("line.ai.decision",DecisionProperties.class),
                Duration.ofSeconds(40),(ticket,snapshot,deadline) -> {
                    if(ticket.kind()!=TurnTicket.Kind.GENERATION){owner.get().completeDecision(ticket,new TimingDecision(TimingDecision.Action.REPLY_NOW,Duration.ZERO,TimingDecision.Reason.COMPLETE_INPUT));return;}
                    try(var permit=owner.get().claimSending(ticket)){
                        assertThat(permit).isNotNull();transport.set(work.retain());sent.countDown();
                        try{assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}
                        catch(InterruptedException failure){throw new AssertionError("Graceful shutdown interrupted SENDING",failure);}
                        assertThat(owner.get().finishSending(ticket,TurnState.ACCEPTED)).isTrue();
                        var writing=work.retain();persistence.execute(() -> {
                            try(writing){BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.USER_FOLLOW,"Final business write",Map.of());}
                        });
                    }
                });owner.set(turns);
        var at=Instant.now();var member=new InboundMember("Ufictional-shutdown","fictional-stop-event","fictional-stop-message","完整虛構停機問題？",at,at,1,"fictional-stop-trace");
        var admission=turns.admit(member,Deadline.current(Duration.ofSeconds(45)),"fictional-stop-credential");
        admission.completion().whenComplete((result,failure) -> work.close());
        CompletableFuture<Void> stopped=null;
        try{
            assertThat(sent.await(2,TimeUnit.SECONDS)).isTrue();stopped=CompletableFuture.runAsync(context::close);
            await().atMost(Duration.ofSeconds(1)).until(() -> !scheduler.isRunning());
            assertThat(stopped).isNotDone();assertThat(recorder.isRunning()).isTrue();assertThat(persistence.getThreadPoolExecutor().isShutdown()).isFalse();
            assertThat(turns.admit(member,Deadline.current(Duration.ofSeconds(45))).admitted()).isFalse();
            release.countDown();assertThat(admission.completion().toCompletableFuture().get(2,TimeUnit.SECONDS).state()).isEqualTo(TurnState.ACCEPTED);
            await().atMost(Duration.ofSeconds(1)).until(scheduler::isIdle);assertThat(stopped).isNotDone();
            assertThat(recorder.isRunning()).isTrue();assertThat(persistence.getThreadPoolExecutor().isShutdown()).isFalse();
            BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.USER_UNFOLLOW,"Final transport cleanup",Map.of());transport.get().close();
            stopped.get(3,TimeUnit.SECONDS);tracker.awaitAll(Duration.ofMillis(10));
            assertThat(persistence.getThreadPoolExecutor().isTerminated()).isTrue();assertThat(recorder.isRunning()).isFalse();
            assertThat(written).extracting(entry -> entry.data().get("event")).containsExactlyInAnyOrder(LogEvents.USER_FOLLOW,LogEvents.USER_UNFOLLOW);
        }finally{release.countDown();if(transport.get()!=null)transport.get().close();work.close();if(stopped!=null)stopped.get(4,TimeUnit.SECONDS);context.close();}
    }
    private DrainingTaskExecutor executor(int phase){var executor=new DrainingTaskExecutor();executor.setPhase(phase);
        executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(3);return executor;}
}
