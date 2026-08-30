package com.shop.shippingservice.outbox;

import com.shop.shippingservice.entity.Shipment;
import com.shop.common.core.constants.OutboxStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShippingEventPublisherImpl implements ShippingEventPublisher {

    private static final String AGGREGATE_TYPE = "Shipment";
    private static final String TOPIC = "shop.shipping.lifecycle.v1";
    private static final String EVENT_TYPE_DELIVERED = "shipping.delivered.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publishDelivered(Shipment shipment, boolean autoDelivered) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", shipment.getOrderId());
        data.put("shipmentId", shipment.getId());
        data.put("carrier", shipment.getCarrier().name());
        data.put("trackingNumber", shipment.getTrackingNumber());
        data.put("autoDelivered", autoDelivered);
        save(shipment.getId(), EVENT_TYPE_DELIVERED, data);
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
            log.error("Failed to serialize outbox payload for shipment {}", aggregateId, ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }
}
