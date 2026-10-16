package com.example.line.ai.aggregation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

/** 验证有序快照、完整原文和共享引用账本，取消后未关闭的快照不能伪造空闲容量。 */
class TurnBufferTest {
    private TurnBuffer buffer(int members, int characters) {
        var env = new MockEnvironment().withProperty("line.ai.aggregation.max-members", "1")
                .withProperty("line.ai.aggregation.max-characters", "1")
                .withProperty("line.ai.aggregation.max-buffered-members", Integer.toString(members))
                .withProperty("line.ai.aggregation.max-buffered-characters", Integer.toString(characters));
        return new TurnBuffer(Binder.get(env).bindOrCreate("line.ai.aggregation", AggregationProperties.class));
    }
    private InboundMember member(String user, long sequence, String text) {
        return new InboundMember(user, "fictional-event-" + sequence, "fictional-message-" + sequence, text,
                Instant.EPOCH, Instant.EPOCH, sequence, "00000000-0000-4000-8000-000000000001");
    }

    @Test
    void snapshotRetainsOriginalTextOrderAndMemberTraceWithoutCopyingTheCharacterCharge() {
        var buffer = buffer(3, 100);
        String text = "你好🙂\n完整保留原文";
        var first = buffer.retain(member("fictional-user", 1, text));
        var second = buffer.retain(member("fictional-user", 2, "第二條"));
        try (var earlier = buffer.snapshot(UUID.randomUUID(), 1, 1, 0, List.of(first));
             var current = buffer.snapshot(UUID.randomUUID(), 1, 2, 0, List.of(first, second))) {
            assertThat(earlier.members()).hasSize(1);
            assertThat(current.members()).extracting(InboundMember::text).containsExactly(text, "第二條");
            assertThat(current.members().get(0).traceId()).isEqualTo("00000000-0000-4000-8000-000000000001");
            assertThatThrownBy(() -> current.members().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(buffer.members()).isEqualTo(2);
            assertThat(buffer.characters()).isEqualTo(text.length() + "第二條".length());
        } finally { first.close(); second.close(); }
        assertThat(buffer.members()).isZero();
        assertThat(buffer.characters()).isZero();
    }

    @Test
    void cancellingTurnDoesNotReleaseAStillRunningSnapshotOrEvictItForANewMember() {
        var buffer = buffer(1, 2);
        var held = buffer.retain(member("fictional-user", 1, "完整"));
        var snapshot = buffer.snapshot(UUID.randomUUID(), 1, 1, 0, List.of(held));
        held.close(); held.close();
        assertThat(buffer.members()).isEqualTo(1);
        assertThat(buffer.characters()).isEqualTo(2);
        assertThat(buffer.retain(member("fictional-user", 2, "新"))).isNull();
        snapshot.close(); snapshot.close();
        assertThatThrownBy(snapshot::members).isInstanceOf(IllegalStateException.class);
        assertThat(buffer.members()).isZero();
        var fresh = buffer.retain(member("fictional-user", 2, "新"));
        assertThat(fresh).isNotNull(); fresh.close();
    }

    @Test
    void characterCapacityIsCheckedBeforeRetentionAndSnapshotValidationDoesNotLeakReferences() {
        var buffer = buffer(4, 4);
        var first = buffer.retain(member("one", 1, "甲乙"));
        var second = buffer.retain(member("one", 2, "丙"));
        var foreignUser = buffer.retain(member("two", 3, "丁"));
        assertThat(buffer.retain(member("one", 4, "超"))).isNull();
        assertThatThrownBy(() -> buffer.snapshot(UUID.randomUUID(), 1, 2, 0, List.of(second, first)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.snapshot(UUID.randomUUID(), 1, 2, 0, List.of(first, foreignUser)))
                .isInstanceOf(IllegalArgumentException.class);
        var other = buffer(1, 1);
        assertThatThrownBy(() -> other.snapshot(UUID.randomUUID(), 1, 1, 0, List.of(first)))
                .isInstanceOf(IllegalArgumentException.class);
        first.close(); second.close(); foreignUser.close();
        assertThat(buffer.members()).isZero();
        assertThatThrownBy(() -> buffer.snapshot(UUID.randomUUID(), 1, 1, 0, List.of(first)))
                .isInstanceOf(IllegalStateException.class);
    }
}
