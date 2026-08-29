package com.shop.inventoryservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import com.shop.inventoryservice.service.InventoryMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@link OutboxEvent} row per domain action in the SAME
 * {@code @Transactional} boundary as the inventory change. The relay
 * ({@code InventoryOutboxRelay}) drains the table to Kafka.
 *
 * <p>Event contract: topic {@code shop.inventory.events.v1}, event types in
 * dot.case ({@code inventory.reserved.v1}, …) — a deliberate CloudEvents-style
 * contract (same style as {@code notification.send.v1}), NOT a typo of
 * product-service's PascalCase {@code shop.product.lifecycle.v1} /
 * {@code ProductCreated}. Both are registered in docs/SERVICE-CATALOG.md; any
 * consumer handles exactly one of the two styles.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalInventoryEventPublisher implements InventoryEventPublisher {

    private static final String AGGREGATE_TYPE = "Inventory";
    private static final String TOPIC = "shop.inventory.events.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final InventoryMetrics metrics;

    @Override
    public void publishReserved(Inventory inventory, Reservation reservation) {
        // HashMap + null-guard: Map.of throws NPE on null values. orderId is
        // OPTIONAL (spec 4.4) — reserving without an orderId is a mainline flow.
        Map<String, Object> data = new HashMap<>();
        data.put("productId", inventory.getProductId());
        data.put("reservationId", reservation.getId());
        data.put("quantity", reservation.getQuantity());
        if (reservation.getOrderId() != null) {
            data.put("orderId", reservation.getOrderId());
        }
        data.put("expiresAt", reservation.getExpiresAt().toString());
        save(inventory, "inventory.reserved.v1", data);
    }

    @Override
    public void publishCommitted(Inventory inventory, Reservation reservation) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", inventory.getProductId());
        data.put("reservationId", reservation.getId());
        data.put("quantity", reservation.getQuantity());
        if (reservation.getOrderId() != null) {
            data.put("orderId", reservation.getOrderId());
        }
        save(inventory, "inventory.committed.v1", data);
    }

    @Override
    public void publishReleased(Inventory inventory, Reservation reservation) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", inventory.getProductId());
        data.put("reservationId", reservation.getId());
        data.put("quantity", reservation.getQuantity());
        if (reservation.getOrderId() != null) {
            data.put("orderId", reservation.getOrderId());
        }
        save(inventory, "inventory.released.v1", data);
    }

    @Override
    public void publishAdjusted(Inventory inventory) {
        save(inventory, "inventory.adjusted.v1", Map.of(
            "productId", inventory.getProductId(),
            "availableQuantity", inventory.getAvailableQuantity()
        ));
    }

    @Override
    public void publishDeleted(Inventory inventory) {
        save(inventory, "inventory.deleted.v1", Map.of(
            "productId", inventory.getProductId()
        ));
    }

    private void save(Inventory inventory, String eventType, Map<String, Object> data) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(inventory.getProductId());   // Kafka partition key
        event.setEventType(eventType);
        event.setTopic(TOPIC);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("eventType", eventType);
        payload.put("occurredAt", Instant.now().toString());
        payload.putAll(data);

        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox payload for product {}", inventory.getProductId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
        metrics.recordEventPublished(eventType);
    }
}
