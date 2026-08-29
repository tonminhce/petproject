package com.shop.productservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.BrandEventPublisher;
import com.shop.productservice.service.ProductMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Brand twin of {@link TransactionalProductEventPublisher}: writes one
 * {@link OutboxEvent} row per brand lifecycle action in the SAME
 * {@code @Transactional} boundary as the entity write. The shared
 * {@link com.shop.productservice.service.OutboxRelay} drains PENDING rows
 * regardless of aggregate type, so no relay change is needed.
 *
 * <p>Deliberately NOT {@code @Transactional} — see the product publisher's
 * javadoc: participating in the caller's transaction is what makes entity
 * change + outbox row atomic.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalBrandEventPublisher implements BrandEventPublisher {

    private static final String AGGREGATE_TYPE_BRAND = "Brand";
    private static final String LIFECYCLE_TOPIC = "shop.brand.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ProductMetrics metrics;

    @Override
    public void publishCreated(Brand brand) {
        save(brand, "BrandCreated");
    }

    @Override
    public void publishUpdated(Brand brand) {
        save(brand, "BrandUpdated");
    }

    @Override
    public void publishDeleted(Brand brand) {
        save(brand, "BrandDeleted");
    }

    private void save(Brand b, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE_BRAND);
        event.setAggregateId(b.getId());
        event.setEventType(eventType);
        event.setTopic(LIFECYCLE_TOPIC);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("eventType", eventType);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("brandId", b.getId());
        payload.put("slug", b.getSlug());
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox event payload for brand {}", b.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
        metrics.recordEventPublished(eventType);
    }
}
