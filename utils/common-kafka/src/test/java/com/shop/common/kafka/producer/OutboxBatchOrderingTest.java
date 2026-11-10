package com.shop.common.kafka.producer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-aggregate ordering rule of a published outbox batch: a failed
 * record blocks the records behind it of the SAME aggregate, and only those.
 */
class OutboxBatchOrderingTest {

    @Test
    void everyRecordSucceeded_everyRecordIsMarkable() {
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("a", "b", "a"), List.of(true, true, true));

        assertThat(markable).containsExactly(true, true, true);
    }

    @Test
    void aFailedRecord_blocksTheLaterRecordsOfTheSameAggregateOnly() {
        // order a1, b1, a2 - a1 fails, so a2 must be retried; b1 is unaffected.
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
        // the batch timed out: the third record has no outcome at all
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
        // "x" fails early; "y" and "z" keep flowing.
        boolean[] markable = OutboxBatchOrdering.markableAsSent(
                List.of("x", "y", "z", "x"), List.of(false, true, true, true));

        assertThat(markable).containsExactly(false, true, true, false);
    }
}
