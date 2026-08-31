package com.shop.paymentservice.outbox;

import com.shop.paymentservice.entity.Payment;
import com.shop.common.core.constants.OutboxStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private static final String AGGREGATE_TYPE = "Payment";
    private static final String TOPIC = "shop.payment.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void publish(Payment payment, String eventType) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", payment.getOrderId());
        data.put("paymentId", payment.getId());
        data.put("amount", payment.getAmount());
        data.put("currency", payment.getCurrency());
        data.put("status", payment.getStatus().name());
        data.put("previousStatus", payment.getPreviousStatus() != null ? payment.getPreviousStatus().name() : null);
        save(payment.getId(), eventType, data);
    }

    private void save(UUID aggregateId, String eventType, Map<String, Object> data) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(aggregateId);
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
            log.error("Failed to serialize outbox payload for payment {}", aggregateId, ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }
}
