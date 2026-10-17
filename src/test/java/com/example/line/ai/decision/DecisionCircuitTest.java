package com.example.line.ai.decision;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** 可控时钟验证全局冷却、单探测、忽略取消与旧代次，不使用真实模型或睡眠。 */
class DecisionCircuitTest {
    private final AtomicLong clock=new AtomicLong();
    private final DecisionCircuit circuit=new DecisionCircuit(Binder.get(new MockEnvironment()
            .withProperty("line.ai.decision.failure-threshold","2")).bindOrCreate("line.ai.decision",DecisionProperties.class),clock::get);
    private void fail(){var permit=circuit.acquire();assertThat(permit).isNotNull();circuit.complete(permit,DecisionCircuit.Outcome.FAILURE);}
    @Test void successResetsConsecutiveFailuresAndIgnoredCancellationDoesNotCount(){
        fail();circuit.complete(circuit.acquire(),DecisionCircuit.Outcome.IGNORED);
        assertThat(circuit.isOpen()).isFalse();circuit.complete(circuit.acquire(),DecisionCircuit.Outcome.SUCCESS);
        fail();assertThat(circuit.isOpen()).isFalse();fail();assertThat(circuit.isOpen()).isTrue();assertThat(circuit.acquire()).isNull();
    }
    @Test void cooldownAllowsExactlyOneProbeAndFailureStartsAnotherFixedCooldown(){
        fail();fail();clock.set(Duration.ofSeconds(29).toNanos());assertThat(circuit.acquire()).isNull();
        clock.set(Duration.ofSeconds(30).toNanos());var probe=circuit.acquire();assertThat(probe.probe()).isTrue();assertThat(circuit.acquire()).isNull();
        circuit.complete(probe,DecisionCircuit.Outcome.FAILURE);clock.set(Duration.ofSeconds(59).toNanos());assertThat(circuit.acquire()).isNull();
        clock.set(Duration.ofSeconds(60).toNanos());circuit.complete(circuit.acquire(),DecisionCircuit.Outcome.SUCCESS);
        assertThat(circuit.isOpen()).isFalse();assertThat(circuit.acquire().probe()).isFalse();
    }
    @Test void oldConcurrentOutcomeCannotCloseNewOpenCircuitAndIgnoredProbeCanBeReplaced(){
        var old=circuit.acquire();fail();fail();circuit.complete(old,DecisionCircuit.Outcome.SUCCESS);assertThat(circuit.isOpen()).isTrue();
        clock.set(Duration.ofSeconds(30).toNanos());var probe=circuit.acquire();circuit.complete(probe,DecisionCircuit.Outcome.IGNORED);
        assertThat(circuit.acquire()).isNotNull();assertThat(circuit.acquire()).isNull();
    }
    @Test void concurrentCooldownCallersCannotStartMultipleProbes()throws Exception{
        fail();fail();clock.set(Duration.ofSeconds(30).toNanos());var pool=Executors.newFixedThreadPool(8);
        try {
            var start=new CountDownLatch(1);var count=new AtomicInteger();var jobs=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<32;i++)jobs.add(pool.submit(() -> {try{start.await();if(circuit.acquire()!=null)count.incrementAndGet();}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}}));
            start.countDown();for(var job:jobs)job.get(3,TimeUnit.SECONDS);assertThat(count).hasValue(1);
        }finally{pool.shutdownNow();}
    }
}
