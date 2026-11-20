package com.shop.common.kafka.producer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublishLedgerTest {

    @Test
    void aConfirmedEventIsPublishedUntilItIsSettled() {
        OutboxPublishLedger ledger = new OutboxPublishLedger();

        ledger.beginPublish(List.of(1L, 2L));
        assertThat(ledger.inFlightEventIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(ledger.publishedEventIds()).isEmpty();

        ledger.confirmPublished(List.of(1L));
        assertThat(ledger.inFlightEventIds()).containsExactly(2L);
        assertThat(ledger.publishedEventIds()).containsExactly(1L);

        ledger.settle(List.of(1L, 2L));
        assertThat(ledger.inFlightEventIds()).isEmpty();
        assertThat(ledger.publishedEventIds()).isEmpty();
    }

    @Test
    void aFailedPublishLeavesTheEventUnpublished() {
        OutboxPublishLedger ledger = new OutboxPublishLedger();

        ledger.beginPublish(List.of(7L));
        ledger.settle(List.of(7L));

        assertThat(ledger.inFlightEventIds()).isEmpty();
        assertThat(ledger.publishedEventIds()).isEmpty();
    }

    @Test
    void nullInputIsIgnored() {
        OutboxPublishLedger ledger = new OutboxPublishLedger();

        ledger.beginPublish(null);
        ledger.confirmPublished(null);
        ledger.settle(null);

        assertThat(ledger.inFlightEventIds()).isEmpty();
        assertThat(ledger.publishedEventIds()).isEmpty();
    }
}
