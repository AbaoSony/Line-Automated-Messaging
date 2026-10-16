package com.example.line.common.execution;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 实际传输仅延续既有工作；删除中的新工作仍被拒绝，作用域不能污染线程复用。 */
class LineSendScopeTest {
    @Test void admittedTransportRetainsExistingGenerationWhileDeletionRejectsNewWork() {
        var tracker=new UserWorkTracker();String user="Ufictional-retained-transport";
        var parent=tracker.register(user);
        try(var barrier=tracker.block(user);var scope=LineSendScope.open(parent)) {
            assertThat(tracker.register(user)).isNull();
            var child=LineSendScope.retainTransport();parent.close();
            assertThatThrownBy(() -> barrier.await(Duration.ofMillis(1))).hasMessageContaining("timed out");
            child.close();barrier.await(Duration.ofMillis(10));
            assertThatThrownBy(parent::retain).hasMessageContaining("already closed");
        }
        assertThat(LineSendScope.retainTransport()).isNull();
    }
    @Test void nestedSendScopesRestoreParentAndReleaseEveryActualTransportLease() {
        var tracker=new UserWorkTracker();var first=tracker.register("Ufictional-first");var second=tracker.register("Ufictional-second");
        try(first;second;var outer=LineSendScope.open(first)) {
            try(var inner=LineSendScope.open(second);var child=LineSendScope.retainTransport()) {
                assertThat(child).isNotNull();
            }
            try(var child=LineSendScope.retainTransport()) {assertThat(child).isNotNull();}
        }
        assertThat(LineSendScope.retainTransport()).isNull();
        try(var firstBarrier=tracker.block("Ufictional-first");var secondBarrier=tracker.block("Ufictional-second")) {
            firstBarrier.await(Duration.ofMillis(10));secondBarrier.await(Duration.ofMillis(10));
        }
    }
}
