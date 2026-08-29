package com.shop.productservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.entity.Category;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.CategoryEventPublisher;
import com.shop.productservice.service.ProductMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Category twin of {@link TransactionalProductEventPublisher}: writes one
 * {@link OutboxEvent} row per category lifecycle action in the SAME
 * {@code @Transactional} boundary as the entity write (catalog taxonomy is now
 * observable downstream — audit item: "Category/Brand lifecycle is silent").
 * The shared {@link com.shop.productservice.service.OutboxRelay} drains PENDING
 * rows regardless of aggregate type, so no relay change is needed.
 *
 * <p>Deliberately NOT {@code @Transactional} — see the product publisher's
 * javadoc: participating in the caller's transaction is what makes entity
 * change + outbox row atomic.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalCategoryEventPublisher implements CategoryEventPublisher {

    private static final String AGGREGATE_TYPE_CATEGORY = "Category";
    private static final String LIFECYCLE_TOPIC = "shop.category.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ProductMetrics metrics;

    @Override
    public void publishCreated(Category category) {
        save(category, "CategoryCreated");
    }

    @Override
    public void publishUpdated(Category category) {
        save(category, "CategoryUpdated");
    }

    @Override
    public void publishDeleted(Category category) {
        save(category, "CategoryDeleted");
    }

    private void save(Category c, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE_CATEGORY);
        event.setAggregateId(c.getId());
        event.setEventType(eventType);
        event.setTopic(LIFECYCLE_TOPIC);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("eventType", eventType);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("categoryId", c.getId());
        payload.put("slug", c.getSlug());
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox event payload for category {}", c.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
        metrics.recordEventPublished(eventType);
    }
}
