package com.shop.ratingservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.entity.Rating;
import com.shop.ratingservice.outbox.OutboxEvent;
import com.shop.ratingservice.outbox.OutboxEventRepository;
import com.shop.ratingservice.repository.RatingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the rating lifecycle outbox row (spec D4) in the SAME transaction as
 * the rating write — the relay publishes it later on topic
 * {@code shop.rating.lifecycle.v1}. The payload carries the recomputed
 * {@code avgRating}/{@code ratingCount} snapshot so consumers stay dumb,
 * idempotent copy-readers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RatingEventService {

    private static final String AGGREGATE_TYPE = "rating";
    private static final String EVENT_TYPE = "rating.submitted.v1";
    private static final String TOPIC = "shop.rating.lifecycle.v1";

    private final RatingRepository ratingRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Joins the caller's transaction (REQUIRED): the snapshot aggregate must
     * see the caller's new/updated row, which is why every caller MUST
     * {@code saveAndFlush} before calling this (plan nit #1) — a plain
     * {@code save} leaves the row unflushed and the JPQL aggregate would
     * compute over stale data.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Rating rating, RatingAction action) {
        Object[] aggregate = ratingRepository.findAggregateByProductId(rating.getProductId()).get(0);
        BigDecimal avgRating = toBigDecimal(aggregate[0]).setScale(2, RoundingMode.HALF_UP);
        int ratingCount = ((Number) aggregate[1]).intValue();

        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(rating.getId());
        event.setEventType(EVENT_TYPE);
        event.setTopic(TOPIC);

        // Field order + names are contract (spec D4) — LinkedHashMap, 13 fields.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("eventType", EVENT_TYPE);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("ratingId", rating.getId());
        payload.put("productId", rating.getProductId());
        payload.put("userId", rating.getUserId());
        payload.put("rating", rating.getRating());
        payload.put("comment", rating.getComment());
        payload.put("verified", rating.isVerified());
        payload.put("action", action.name());
        payload.put("visible", !rating.isHidden());
        payload.put("avgRating", avgRating);
        payload.put("ratingCount", ratingCount);

        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox payload for rating {}", rating.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }

    // JPQL AVG over an integral column returns Double on some Hibernate
    // versions, BigDecimal on others — normalize before scaling.
    private BigDecimal toBigDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(String.valueOf(raw));
    }
}
