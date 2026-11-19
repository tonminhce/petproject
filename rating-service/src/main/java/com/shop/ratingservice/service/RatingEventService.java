package com.shop.ratingservice.service;

import com.shop.common.kafka.contract.KafkaTopicContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.entity.Rating;
import com.shop.ratingservice.metrics.RatingMetrics;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class RatingEventService {

    private static final String AGGREGATE_TYPE = "rating";
    private static final String EVENT_TYPE = "rating.submitted.v1";
    private static final String TOPIC = KafkaTopicContract.SHOP_RATING_LIFECYCLE;

    private final RatingRepository ratingRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final RatingMetrics ratingMetrics;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Rating rating, RatingAction action) {
        Object[] aggregate = ratingRepository.findAggregateByProductId(rating.getProductId()).get(0);
        BigDecimal avgRating = toBigDecimal(aggregate[0]).setScale(2, RoundingMode.HALF_UP);
        int ratingCount = ((Number) aggregate[1]).intValue();

        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);

        event.setAggregateId(rating.getProductId());
        event.setEventType(EVENT_TYPE);
        event.setTopic(TOPIC);

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
        ratingMetrics.recordSubmitted(action);
    }

    private BigDecimal toBigDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(String.valueOf(raw));
    }
}
