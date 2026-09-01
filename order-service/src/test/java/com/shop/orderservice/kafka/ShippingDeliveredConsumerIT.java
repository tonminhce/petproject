package com.shop.orderservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OutboxEventRepository;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shipping-delivered consumer against the REAL Kafka wire (H-1): the fleet
 * relay (shipping-service {@code ShippingOutboxRelay}) publishes the outbox
 * payload STRING through the {@code JsonKafkaSerializer} producer, so records
 * arrive DOUBLE-ENCODED — a JSON string token wrapping the event JSON. This
 * IT publishes through the SAME context {@code KafkaTemplate}, reproducing
 * the exact wire shape. Under the previous {@code JsonDeserializer} wiring
 * the token failed value deserialization before the listener ever ran — the
 * fleet's shipping→order delivered transition silently dropped every event;
 * with the H-1 String base the event is unwrapped-once at the consumer
 * boundary and SHIPPED orders transition to DELIVERED. Poison tokens and
 * unknown orders are contained ack-skips — the partition keeps advancing.
 */
class ShippingDeliveredConsumerIT extends AbstractOrderServiceIT {

    private static final String TOPIC = "shop.shipping.lifecycle.v1";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    private static final UUID SHIPMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${shop.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * The order-service base leaves the KafkaProperties default in place —
     * pin {@code earliest} like the shipping/notification IT bases so the
     * listener consumes this IT's records deterministically from offset 0 of
     * the fresh container topic (prod runs {@code latest} via application.yml;
     * the class-level @DynamicPropertySource here only affects this IT's
     * context).
     */
    @DynamicPropertySource
    static void kafkaEarliest(DynamicPropertyRegistry r) {
        r.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @BeforeEach
    void resetState() {
        outboxEventRepository.deleteAllInBatch();
    }

    private Order shippedOrder() {
        Order order = Order.builder()
            .userId(UUID.randomUUID())
            .status(OrderStatus.SHIPPED)
            .subtotal(new BigDecimal("100.00"))
            .taxAmount(BigDecimal.ZERO)
            .discountAmount(BigDecimal.ZERO)
            .total(new BigDecimal("100.00"))
            .shippedAt(java.time.Instant.now())
            .build();
        return orderRepository.save(order);
    }

    /** Publishes EXACTLY like ShippingOutboxRelay: payload String via the fleet KafkaTemplate. */
    private void publishExactlyLikeRelay(String payloadJson, UUID orderId) {
        try {
            kafkaTemplate.send(TOPIC, orderId.toString(), payloadJson).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mirrors ShippingEventPublisherImpl.publishDelivered. */
    private String deliveredPayload(String eventId, UUID orderId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "shipping.delivered.v1");
        payload.put("occurredAt", java.time.Instant.now().toString());
        payload.put("orderId", orderId.toString());
        payload.put("shipmentId", SHIPMENT_ID.toString());
        payload.put("carrier", "MANUAL");
        payload.put("trackingNumber", "GHN-IT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("autoDelivered", false);
        return writeJson(objectMapper.valueToTree(payload));
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("double-encoded shipping.delivered.v1 transitions the SHIPPED order to DELIVERED")
    void doubleEncodedDeliveredEventTransitionsOrderToDelivered() {
        Order order = shippedOrder();

        publishExactlyLikeRelay(deliveredPayload(UUID.randomUUID().toString(), order.getId()), order.getId());

        await().atMost(AWAIT).untilAsserted(() -> {
            Order fresh = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(fresh.getStatus()).as("delivered transition applied").isEqualTo(OrderStatus.DELIVERED);
            assertThat(fresh.getDeliveredAt()).isNotNull();
        });
        // publishStatusChanged emitted an order.updated.v1 outbox row in the
        // same transaction (existence only — the scheduled relay may drain it).
        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(outboxEventRepository.findAll().stream()
                .filter(r -> "order.updated.v1".equals(r.getEventType()))
                .filter(r -> order.getId().equals(r.getAggregateId()))
                .findFirst()).as("status-changed outbox row emitted").isPresent());
    }

    @Test
    @DisplayName("real kafka record value is a JSON string token wrapping the event (H-1 wire shape)")
    void wireShapeIsDoubleEncodedOnTheRealBroker() {
        Order order = shippedOrder();
        publishExactlyLikeRelay(deliveredPayload(UUID.randomUUID().toString(), order.getId()), order.getId());

        ConsumerRecord<String, String> record = awaitRecord(TOPIC, order.getId().toString());

        assertThat(record).as("record on %s with key=orderId", TOPIC).isNotNull();
        JsonNode node = readTree(record.value());
        // Under the old JsonDeserializer-only config this exact record was
        // dropped before the listener ever ran.
        assertThat(node.isTextual())
            .as("H-1 pin: wire value must be double-encoded (JSON string token)")
            .isTrue();
        JsonNode unwrapped = readTree(node.textValue());
        assertThat(unwrapped.get("eventType").textValue()).isEqualTo("shipping.delivered.v1");
        assertThat(unwrapped.get("orderId").textValue()).isEqualTo(order.getId().toString());
    }

    @Test
    @DisplayName("poison double-encoded token never stalls the partition — a later delivered event still applies")
    void poisonTokenDoesNotStallPartition() {
        Order order = shippedOrder();
        publishExactlyLikeRelay("{\"broken\"", order.getId());

        publishExactlyLikeRelay(deliveredPayload(UUID.randomUUID().toString(), order.getId()), order.getId());

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED));
    }

    @Test
    @DisplayName("delivered event for an unknown order is a contained ack-skip — partition keeps advancing")
    void unknownOrderIsContainedAckSkipAndPartitionAdvances() {
        publishExactlyLikeRelay(deliveredPayload(UUID.randomUUID().toString(), UUID.randomUUID()), UUID.randomUUID());

        Order marker = shippedOrder();
        publishExactlyLikeRelay(deliveredPayload(UUID.randomUUID().toString(), marker.getId()), marker.getId());

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(orderRepository.findById(marker.getId()).orElseThrow().getStatus())
                .as("marker delivered — partition advanced past the unknown-order record")
                .isEqualTo(OrderStatus.DELIVERED));
    }

    // --- helpers ---

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "shipping-delivered-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }
}
