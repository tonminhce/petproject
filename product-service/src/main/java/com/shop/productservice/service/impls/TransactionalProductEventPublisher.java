package com.shop.productservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.ProductEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@link OutboxEvent} row per domain action in the SAME
 * {@code @Transactional} boundary as the entity write. A subsequent relay
 * (see {@code OutboxRelay}) drains the table and publishes to Kafka.
 *
 * <p>The publisher itself is intentionally NOT {@code @Transactional}: the
 * caller's transaction (e.g. {@code ProductServiceImpl.create/update/delete})
 * provides the boundary. If no transaction is open the row would still be
 * committed individually, defeating atomicity — by participating in an
 * existing transaction we guarantee the entity change and the outbox row
 * either both commit or both roll back.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalProductEventPublisher implements ProductEventPublisher {

    private static final String AGGREGATE_TYPE_PRODUCT = "Product";
    private static final String LIFECYCLE_TOPIC = "shop.product.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publishCreated(Product product) {
        save(product, "ProductCreated");
    }

    @Override
    public void publishUpdated(Product product) {
        save(product, "ProductUpdated");
    }

    @Override
    public void publishDeleted(Product product) {
        save(product, "ProductDeleted");
    }

    private void save(Product p, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE_PRODUCT);
        event.setAggregateId(p.getId());
        event.setEventType(eventType);
        event.setTopic(LIFECYCLE_TOPIC);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("eventType", eventType);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("productId", p.getId());
        payload.put("slug", p.getSlug());
        payload.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox event payload for product {}", p.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }
}
