package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxRelayOrderTest {

    @Test
    void claimedRowsAreOrderedByAscendingId() {
        OutboxEvent first = row(1L);
        OutboxEvent second = row(2L);
        OutboxEvent third = row(3L);

        List<OutboxEvent> scrambled = new ArrayList<>(List.of(third, first, second));

        List<OutboxEvent> ordered = PaymentOutboxRelay.orderById(scrambled);

        assertThat(ordered).extracting(OutboxEvent::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void orderingIsStableForRowsThatShareAnAggregate() {
        UUID aggregate = UUID.randomUUID();
        OutboxEvent captured = row(1L, aggregate);
        OutboxEvent refunded = row(2L, aggregate);

        List<OutboxEvent> ordered = PaymentOutboxRelay.orderById(new ArrayList<>(List.of(refunded, captured)));

        assertThat(ordered).extracting(OutboxEvent::getId).containsExactly(1L, 2L);
        assertThat(ordered.get(0).getAggregateId()).isEqualTo(aggregate);
    }

    private static OutboxEvent row(Long id) {
        return row(id, UUID.randomUUID());
    }

    private static OutboxEvent row(Long id, UUID aggregate) {
        return OutboxEvent.builder()
                .id(id)
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(aggregate)
                .eventType("payment.test.v1")
                .topic("shop.payment.test.v1")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }
}
