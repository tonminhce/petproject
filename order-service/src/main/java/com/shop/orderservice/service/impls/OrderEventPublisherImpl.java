package com.shop.orderservice.service.impls;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.orderservice.repository.OutboxEventRepository;
import com.shop.orderservice.service.OrderEventPublisher;
import com.shop.orderservice.service.OrderMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisherImpl implements OrderEventPublisher {

    private static final String AGGREGATE_TYPE = "Order";
    private static final String TOPIC = "shop.order.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OrderMetrics metrics;

    @Override
    public void publishCreated(Order order, List<OrderItem> items) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("userId", order.getUserId());
        data.put("status", order.getStatus().name());
        data.put("items", items.stream().map(OrderEventPublisherImpl::itemToMap).toList());
        data.put("subtotal", order.getSubtotal());
        data.put("taxAmount", order.getTaxAmount());
        data.put("discountAmount", order.getDiscountAmount());
        data.put("total", order.getTotal());
        if (order.getCouponCode() != null) {
            data.put("couponCode", order.getCouponCode());
        }
        data.put("createdAt", order.getCreatedAt().toString());
        save(order.getId(), "order.created.v1", data);
    }

    @Override
    public void publishStatusChanged(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("status", order.getStatus().name());
        Instant transitionedAt = switch (order.getStatus()) {
            case CONFIRMED -> order.getConfirmedAt();
            case SHIPPED -> order.getShippedAt();
            case DELIVERED -> order.getDeliveredAt();
            default -> order.getUpdatedAt();
        };
        data.put("transitionedAt", transitionedAt != null ? transitionedAt.toString() : Instant.now().toString());
        save(order.getId(), "order.updated.v1", data);
    }

    @Override
    public void publishCancelled(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("cancelledAt", order.getCancelledAt() != null ? order.getCancelledAt().toString() : Instant.now().toString());
        // P2-4 — MVP cannot determine refund status (no payment-service integration yet).
        // Hardcode false + TODO for Phase 8 (payment-service) to wire real refund state.
        // Original condition `status == CANCELLED && total != null` was ALWAYS true.
        data.put("refunded", false);
        save(order.getId(), "order.cancelled.v1", data);
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
            log.error("Failed to serialize outbox payload for order {}", aggregateId, ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
        metrics.recordEventPublished(eventType);
    }

    private static Map<String, Object> itemToMap(OrderItem item) {
        Map<String, Object> map = new HashMap<>();
        map.put("productId", item.getProductId());
        map.put("productTitle", item.getProductTitle());
        map.put("quantity", item.getQuantity());
        map.put("unitPrice", item.getUnitPrice());
        map.put("lineTotal", item.getLineTotal());
        return map;
    }
}