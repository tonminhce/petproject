package com.shop.notificationservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLifecycleEventTest {

    @Test
    void orderLifecycleEvent_isJavaRecord() {
        assertThat(OrderLifecycleEvent.class.isRecord())
                .as("OrderLifecycleEvent must be a Java record (H26)")
                .isTrue();
        RecordComponent[] components = OrderLifecycleEvent.class.getRecordComponents();
        assertThat(components).isNotNull();
        assertThat(components)
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "eventId", "eventType", "occurredAt", "orderId", "userId",
                        "status", "subtotal", "taxAmount", "discountAmount", "total",
                        "transitionedAt", "cancelledAt", "refunded", "items");
    }

    @Test
    void record_hasNoLombokStereotypeAnnotations() {
        assertThat(OrderLifecycleEvent.class.isAnnotationPresent(
                lombok.Setter.class))
                .as("@Setter is forbidden on a record (records are immutable)")
                .isFalse();
        assertThat(OrderLifecycleEvent.class.isAnnotationPresent(
                lombok.NoArgsConstructor.class))
                .as("@NoArgsConstructor is forbidden on a record (canonical ctor is the only construction API)")
                .isFalse();
    }

    @Test
    void record_roundTripsThroughJacksonCanonicalCtor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        UUID orderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Instant transitionedAt = Instant.parse("2026-09-03T10:15:30Z");
        Instant cancelledAt = Instant.parse("2026-09-03T11:00:00Z");
        OrderLifecycleEvent original = new OrderLifecycleEvent(
                "evt-1", "order.created.v1", "2026-09-03T10:00:00Z",
                orderId, userId, "NEW",
                new BigDecimal("100.00"), new BigDecimal("8.00"),
                new BigDecimal("0.00"), new BigDecimal("108.00"),
                transitionedAt, cancelledAt, Boolean.FALSE,
                List.of(Map.of("sku", "SKU-1", "quantity", 2)));

        String json = mapper.writeValueAsString(original);
        JsonNode node = mapper.readTree(json);
        OrderLifecycleEvent rebound = mapper.treeToValue(node, OrderLifecycleEvent.class);

        assertThat(rebound.eventId()).isEqualTo("evt-1");
        assertThat(rebound.eventType()).isEqualTo("order.created.v1");
        assertThat(rebound.occurredAt()).isEqualTo("2026-09-03T10:00:00Z");
        assertThat(rebound.orderId()).isEqualTo(orderId);
        assertThat(rebound.userId()).isEqualTo(userId);
        assertThat(rebound.status()).isEqualTo("NEW");
        assertThat(rebound.subtotal().compareTo(new BigDecimal("100.00"))).isZero();
        assertThat(rebound.taxAmount().compareTo(new BigDecimal("8.00"))).isZero();
        assertThat(rebound.discountAmount().compareTo(new BigDecimal("0.00"))).isZero();
        assertThat(rebound.total().compareTo(new BigDecimal("108.00"))).isZero();
        assertThat(rebound.transitionedAt()).isEqualTo(transitionedAt);
        assertThat(rebound.cancelledAt()).isEqualTo(cancelledAt);
        assertThat(rebound.refunded()).isFalse();
        assertThat(rebound.items()).hasSize(1);
    }
}
