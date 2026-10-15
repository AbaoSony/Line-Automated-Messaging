package com.example.line.common.execution;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;

/** 用可控单调时钟验证阶段不重置预算，以及删除屏障、等待失败和旧请求墓碑。 */
class DeadlineAndWorkTrackerTest {
    @Test void stagesShareEntryDeadlineInsteadOfResettingIt(){
        var tick=new AtomicLong(100);var deadline=Deadline.from(100,Duration.ofSeconds(15),tick::get);
        tick.addAndGet(Duration.ofSeconds(10).toNanos());assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(5));
        assertThat(deadline.limit(Duration.ofSeconds(8))).isEqualTo(Duration.ofSeconds(5));
        tick.addAndGet(Duration.ofSeconds(6).toNanos());assertThat(deadline.remaining()).isZero();assertThat(deadline.permits(Duration.ZERO)).isFalse();
    }
    @Test void barrierRejectsNewWorkWaitsForOldAndRejectsOldReceiptAfterCommit(){
        var tracker=new UserWorkTracker();long old=System.nanoTime();
        var work=tracker.register("Ufictional-user");
        try(var barrier=tracker.block("Ufictional-user")){
            assertThat(tracker.register("Ufictional-user")).isNull();
            assertThatThrownBy(() -> barrier.await(Duration.ofMillis(1))).hasMessageContaining("timed out");
            work.close();barrier.await(Duration.ofMillis(10));barrier.committed();
        }
        MDC.put("received_nanos",Long.toString(old));
        try{assertThat(tracker.register("Ufictional-user")).isNull();}finally{MDC.clear();}
        try(var newMessage=tracker.register("Ufictional-user")){assertThat(newMessage).isNotNull();}
    }
    @Test void failedDeleteDoesNotPermanentlyBlockOrPretendToDeleteInFlightWork(){
        var tracker=new UserWorkTracker();var work=tracker.register("Ufictional-user");
        try(var barrier=tracker.block("Ufictional-user")){
            assertThatThrownBy(() -> barrier.await(Duration.ofMillis(1))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(tracker.blocked("Ufictional-user")).isFalse();work.close();
        try(var next=tracker.register("Ufictional-user")){assertThat(next).isNotNull();}
    }
}
