package com.shop.common.kafka.producer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBatchOrderingTest {

    @Test
    void everyRecordSucceeded_everyRecordIsMarkable() {
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("a", "b", "a"), List.of(true, true, true));

        assertThat(markable).containsExactly(true, true, true);
    }

    @Test
    void aFailedRecord_blocksTheLaterRecordsOfTheSameAggregateOnly() {
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("a", "b", "a"), List.of(false, true, true));

        assertThat(markable).containsExactly(false, true, false);
    }

    @Test
    void aFailedRecordBlocksEveryLaterRecordOfItsAggregate() {
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("a", "a", "a"), List.of(true, false, true));

        assertThat(markable).containsExactly(true, false, false);
    }

    @Test
    void aRecordThatNeverCompletedIsTreatedAsFailed() {
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("a", "b"), List.of(true));

        assertThat(markable).containsExactly(true, false);
    }

    @Test
    void anEmptyBatchMarksNothing() {
        assertThat(OutboxBatchOrdering.markableAsSent(List.of(), List.of())).isEmpty();
        assertThat(OutboxBatchOrdering.markableAsSent(null, null)).isEmpty();
    }

    @Test
    void aggregatesAreIndependentOfEachOther() {
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("x", "y", "z", "x"), List.of(false, true, true, true));

        assertThat(markable).containsExactly(false, true, true, false);
    }
}
