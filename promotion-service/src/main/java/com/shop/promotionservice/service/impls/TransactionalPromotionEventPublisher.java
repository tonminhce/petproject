package com.shop.promotionservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.entity.OutboxEvent;
import com.shop.promotionservice.repository.OutboxEventRepository;
import com.shop.promotionservice.service.PromotionEventPublisher;
import com.shop.promotionservice.service.PromotionMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@link OutboxEvent} row per domain action in the SAME
 * transactional boundary as the reservation lifecycle change. The relay
 * ({@code PromotionOutboxRelay}) drains the table to Kafka.
 *
 * <p>Event contract (spec §5.5): topic {@code shop.promotion.lifecycle.v1},
 * event types in dot.case ({@code promotion.reserved.v1}, …) — the same
 * dot-style payload contract as inventory-service's
 * {@code inventory.*.v1} events, NOT product-service's PascalCase style.
 * {@code previousStatus} on {@code promotion.released.v1} lets consumers
 * distinguish a plain release (quota returned unused) from a half-commit
 * rollback (confirm-flow compensation).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalPromotionEventPublisher implements PromotionEventPublisher {

    private static final String AGGREGATE_TYPE = "Campaign";
    private static final String TOPIC = "shop.promotion.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final PromotionMetrics metrics;

    @Override
    public void publishReserved(Campaign campaign, CouponUsageReservation reservation) {
        Map<String, Object> data = new HashMap<>();
        data.put("campaignId", campaign.getId());
        data.put("code", campaign.getCode());
        data.put("userId", reservation.getUserId());
        data.put("orderId", reservation.getOrderId());
        data.put("reservationId", reservation.getId());
        data.put("discountAmount", reservation.getDiscountAmount());
        save(campaign, "promotion.reserved.v1", data);
    }

    @Override
    public void publishCommitted(Campaign campaign, CouponUsageReservation reservation) {
        Map<String, Object> data = new HashMap<>();
        data.put("campaignId", campaign.getId());
        data.put("code", campaign.getCode());
        data.put("userId", reservation.getUserId());
        data.put("orderId", reservation.getOrderId());
        data.put("reservationId", reservation.getId());
        data.put("discountAmount", reservation.getDiscountAmount());
        data.put("committedAt", reservation.getCommittedAt().toString());
        save(campaign, "promotion.committed.v1", data);
    }

    @Override
    public void publishReleased(Campaign campaign, CouponUsageReservation reservation, String previousStatus) {
        Map<String, Object> data = new HashMap<>();
        data.put("campaignId", campaign.getId());
        data.put("code", campaign.getCode());
        data.put("userId", reservation.getUserId());
        data.put("orderId", reservation.getOrderId());
        data.put("reservationId", reservation.getId());
        data.put("discountAmount", reservation.getDiscountAmount());
        data.put("releasedAt", reservation.getReleasedAt().toString());
        // previousStatus ("PENDING"|"COMMITTED") — spec §5.5: distinguishes a
        // plain release (reserve→release, quota returned unused) from a
        // half-commit rollback (confirm-flow compensation) without a separate
        // event type.
        data.put("previousStatus", previousStatus);
        save(campaign, "promotion.released.v1", data);
    }

    private void save(Campaign campaign, String eventType, Map<String, Object> data) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(campaign.getId());           // Kafka partition key
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
            log.error("Failed to serialize outbox payload for campaign {}", campaign.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
        metrics.recordEventPublished(eventType);
    }
}
