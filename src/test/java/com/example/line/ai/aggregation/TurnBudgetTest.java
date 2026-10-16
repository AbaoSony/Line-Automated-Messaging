package com.example.line.ai.aggregation;

import com.example.line.common.execution.Deadline;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用可控单调时钟验证首条计时、窗口压缩和所有正文尝试共享截止，不依赖睡眠。 */
class TurnBudgetTest {
    private final AtomicLong clock = new AtomicLong();
    private final AggregationProperties properties = Binder.get(new MockEnvironment())
            .bindOrCreate("line.ai.aggregation", AggregationProperties.class);

    @Test
    void defaultLedgerKeepsSixSecondsForCollectionAndCountsEarlierAdmissionTime() {
        clock.set(seconds(4));
        var budget = create(45);
        assertThat(budget.totalDeadline().remaining()).isEqualTo(Duration.ofSeconds(41));
        assertThat(budget.collectionDeadline().remaining()).isEqualTo(Duration.ofSeconds(2));
        assertThat(budget.collectionDeadline().expiresNanos()).isEqualTo(seconds(6));
        clock.set(seconds(6));
        assertThat(budget.collectionDeadline().permits(Duration.ZERO)).isFalse();
    }

    @Test
    void newMessageAndLatePromotionCannotRestartCollectionOrTotalDeadline() {
        var original = create(45);
        clock.set(seconds(5));
        var merged = original.including(Deadline.from(seconds(5), Duration.ofSeconds(45), clock::get));
        assertThat(merged.totalDeadline().expiresNanos()).isEqualTo(seconds(45));
        assertThat(merged.collectionDeadline().expiresNanos()).isEqualTo(seconds(6));
        clock.set(seconds(10));
        assertThat(merged.collectionDeadline().remaining()).isZero();
        assertThat(merged.totalDeadline().remaining()).isEqualTo(Duration.ofSeconds(35));
    }

    @Test
    void shorterMemberBudgetCanOnlyTightenCollectionAndFrozenGeneration() {
        clock.set(seconds(1));
        var generating = create(45).beginGeneration(Duration.ofSeconds(40));
        var shortened = generating.including(Deadline.from(0, Duration.ofSeconds(40), clock::get));
        assertThat(shortened.totalDeadline().expiresNanos()).isEqualTo(seconds(40));
        assertThat(shortened.collectionDeadline().expiresNanos()).isEqualTo(seconds(1));
        assertThat(shortened.generationDeadline().expiresNanos()).isEqualTo(seconds(37));
        assertThat(generating.totalDeadline().expiresNanos()).isEqualTo(seconds(45));
    }

    @Test
    void generationStageLimitStartsOnceAndRetriesOrRegenerationCannotExtendIt() {
        clock.set(seconds(6));
        var first = create(45).beginGeneration(Duration.ofSeconds(10));
        assertThat(first.generationDeadline().expiresNanos()).isEqualTo(seconds(16));
        clock.set(seconds(12));
        var retry = first.beginGeneration(Duration.ofSeconds(40));
        assertThat(retry).isSameAs(first);
        assertThat(retry.generationDeadline().remaining()).isEqualTo(Duration.ofSeconds(4));
        clock.set(seconds(17));
        assertThat(retry.beginGeneration(Duration.ofSeconds(40)).generationDeadline().remaining()).isZero();
    }

    @Test
    void bodyDeadlineAlwaysLeavesSendReserveEvenWhenModelStageIsLonger() {
        clock.set(seconds(10));
        var budget = create(45).beginGeneration(Duration.ofSeconds(40));
        assertThat(budget.generationDeadline().expiresNanos()).isEqualTo(seconds(42));
        assertThat(budget.totalDeadline().expiresNanos() - budget.generationDeadline().expiresNanos())
                .isEqualTo(seconds(3));
    }

    @Test
    void insufficientPlanningReserveClosesCollectionWithoutInventingExtraTime() {
        var budget = create(20);
        assertThat(budget.collectionDeadline().remaining()).isZero();
        assertThat(budget.beginGeneration(Duration.ofSeconds(40)).generationDeadline().expiresNanos())
                .isEqualTo(seconds(17));
    }

    @Test
    void alreadyExpiredMemberClosesEveryPreGenerationWindowWithoutResettingOrigin() {
        var budget = create(45).including(Deadline.from(-seconds(50), Duration.ofSeconds(45), clock::get));
        assertThat(budget.totalDeadline().startedNanos()).isZero();
        assertThat(budget.totalDeadline().remaining()).isZero();
        assertThat(budget.collectionDeadline().expiresNanos()).isEqualTo(-seconds(5));
        assertThatThrownBy(() -> budget.beginGeneration(Duration.ofSeconds(40)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noGenerationStartsAtSendReserveBoundaryAndNoDeadlineExistsBeforeFirstStart() {
        var budget = create(45);
        assertThatThrownBy(budget::generationDeadline).isInstanceOf(IllegalStateException.class);
        clock.set(seconds(42));
        assertThatThrownBy(() -> budget.beginGeneration(Duration.ofSeconds(40)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidLifetimeSendReserveAndStageLimitAreRejected() {
        assertThatThrownBy(() -> create(51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(3)).isInstanceOf(IllegalArgumentException.class);
        var deadline = Deadline.from(0, Duration.ofSeconds(45), clock::get);
        assertThatThrownBy(() -> TurnBudget.create(deadline, properties, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(45).beginGeneration(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(45).beginGeneration(Duration.ofSeconds(51)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TurnBudget create(int totalSeconds) {
        return TurnBudget.create(Deadline.from(0, Duration.ofSeconds(totalSeconds), clock::get),
                properties, Duration.ofSeconds(3));
    }

    private long seconds(int value) { return Duration.ofSeconds(value).toNanos(); }
}
