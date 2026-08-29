package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import com.shop.inventoryservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container-backed relay test — all Testcontainers/property bootstrap lives in
 * {@link AbstractIntegrationTest}; only the service-specific poll-interval
 * override is declared here.
 */
class InventoryOutboxRelayIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void registerInventoryProps(DynamicPropertyRegistry registry) {
        registry.add("inventory.outbox.poll-interval-ms", () -> "200");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired InventoryOutboxRelay relay;

    @Test
    void relay_sendsPendingEventsAndMarksSent() throws Exception {
        OutboxEvent event = OutboxEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .aggregateType("Inventory")
            .aggregateId(UUID.randomUUID())
            .eventType("inventory.adjusted.v1")
            .topic("shop.inventory.events.v1")
            .payload("{\"productId\":\"x\"}")
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .build();
        outboxRepo.save(event);

        relay.relay();

        List<OutboxEvent> after = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 100));
        assertThat(after).isEmpty();

        OutboxEvent sent = outboxRepo.findById(event.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.getSentAt()).isNotNull();
    }
}
