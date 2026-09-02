package com.shop.productservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Rating lifecycle consumer against the REAL Kafka wire (R1 + H-1): this IT
 * publishes through the SAME production path as rating-service's
 * {@code RatingOutboxRelay} — the context {@link KafkaMessagePublisher} (R1
 * single-encode: the outbox payload STRING forwarded as raw UTF-8 bytes), so
 * records arrive SINGLE-ENCODED UTF-8 JSON. Under the previous
 * {@code JsonDeserializer} wiring real records failed value deserialization
 * before the listener ever ran — the fleet's rating→product aggregation
 * silently dropped every event; with the H-1 String base the event binds
 * directly (and a legacy double-encoded token from pre-R1 producers would be
 * unwrapped-once — tolerance pinned in {@link MediaDeletedConsumerIT}). Poison
 * tokens must never stall the partition.
 */
class RatingLifecycleConsumerIT extends AbstractIntegrationTest {

    private static final String TOPIC = "shop.rating.lifecycle.v1";
    private static final String PRODUCT_TOPIC = "shop.product.lifecycle.v1";

    private static final UUID PRODUCT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID RATING_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID USER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private KafkaMessagePublisher kafkaMessagePublisher;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The product base leaves the KafkaProperties/application.yml default in
     * place — pin {@code earliest} like the shipping/notification IT bases so
     * the listener consumes this IT's records deterministically from offset 0
     * of the fresh container topic (prod runs {@code latest}; a record
     * published around container-join time is otherwise silently skipped —
     * the exact class of bug this epic hunts). The class-level
     * {@code @DynamicPropertySource} here only affects this IT's context.
     */
    @DynamicPropertySource
    static void kafkaEarliest(DynamicPropertyRegistry r) {
        r.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @BeforeEach
    void resetState() {
        productRepository.deleteAllInBatch();
        outboxRepository.deleteAllInBatch();
    }

    private Product product(String title) {
        Product p = Product.builder()
            .title(title)
            .slug(title.toLowerCase().replace(' ', '-') + "-" + UUID.randomUUID())
            .sku("RAT-" + UUID.randomUUID().toString().substring(0, 8))
            .priceUnit(new BigDecimal("999.00"))
            .quantity(10)
            .status(ProductStatus.ACTIVE)
            .build();
        return productRepository.save(p);
    }

    /** Publishes EXACTLY like RatingOutboxRelay: payload String via the fleet KafkaMessagePublisher (R1 single-encode). */
    private void publishExactlyLikeRelay(String payloadJson, UUID productId) {
        kafkaMessagePublisher.publish(TOPIC, productId.toString(), payloadJson);
    }

    /** Mirrors RatingEventService.record: 13 contract fields, LinkedHashMap order. */
    private String ratingPayload(String eventId, UUID productId, String action, String avgRating, int ratingCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "rating.submitted.v1");
        payload.put("occurredAt", "2026-09-01T10:00:00Z");
        payload.put("ratingId", RATING_ID.toString());
        payload.put("productId", productId.toString());
        payload.put("userId", USER_ID.toString());
        payload.put("rating", 5);
        payload.put("comment", "Great product, highly recommend");
        payload.put("verified", true);
        payload.put("action", action);
        payload.put("visible", true);
        payload.put("avgRating", new BigDecimal(avgRating));
        payload.put("ratingCount", ratingCount);
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
    @DisplayName("rating.submitted.v1 (production single-encoded wire) copies the snapshot onto the product and emits ProductUpdated")
    void ratingSubmittedUpdatesAggregatesAndEmitsProductUpdated() {
        Product product = product("iPhone 15");
        outboxRepository.deleteAllInBatch(); // isolate this test's ProductUpdated assertions

        publishExactlyLikeRelay(ratingPayload(UUID.randomUUID().toString(), product.getId(), "CREATED", "4.50", 2),
            product.getId());

        AtomicReference<OutboxEvent> updated = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Product fresh = productRepository.findById(product.getId()).orElseThrow();
            assertThat(fresh.getAvgRating()).as("avgRating snapshot copied").isEqualByComparingTo("4.50");
            assertThat(fresh.getRatingCount()).as("ratingCount snapshot copied").isEqualTo(2);
            List<OutboxEvent> rows = outboxRepository.findAll().stream()
                .filter(r -> "ProductUpdated".equals(r.getEventType()))
                .toList();
            assertThat(rows).as("one ProductUpdated outbox row per applied rating event").hasSize(1);
            updated.set(rows.get(0));
        });

        OutboxEvent row = updated.get();
        assertThat(row.getAggregateId()).isEqualTo(product.getId());
        assertThat(row.getTopic()).isEqualTo(PRODUCT_TOPIC);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("production wire (KafkaMessagePublisher) is SINGLE-ENCODED JSON on the real broker (R1)")
    void wireShapeIsSingleEncodedOnTheRealBroker() {
        Product product = product("Wire Product");
        publishExactlyLikeRelay(ratingPayload(UUID.randomUUID().toString(), product.getId(), "CREATED", "4.00", 1),
            product.getId());

        ConsumerRecord<String, String> record = awaitRecord(TOPIC, product.getId().toString());

        assertThat(record).as("record on %s with key=productId", TOPIC).isNotNull();
        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // R1: the payload String is forwarded as raw UTF-8 bytes — the raw
        // wire value IS the event object, not a JSON string token.
        assertThat(node.isTextual())
            .as("R1 pin: wire value must be single-encoded (a JSON object, not a string token)")
            .isFalse();
        assertThat(node.get("eventType").textValue()).isEqualTo("rating.submitted.v1");
        assertThat(node.get("productId").textValue()).isEqualTo(product.getId().toString());
        assertThat(node.get("avgRating").decimalValue()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("poison bytes never stall the partition — a later rating event still applies")
    void poisonTokenDoesNotStallPartition() {
        Product product = product("Poison Product");
        publishExactlyLikeRelay("{\"broken\"", product.getId());

        publishExactlyLikeRelay(ratingPayload(UUID.randomUUID().toString(), product.getId(), "CREATED", "3.50", 1),
            product.getId());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Product fresh = productRepository.findById(product.getId()).orElseThrow();
            assertThat(fresh.getAvgRating()).isEqualByComparingTo("3.50");
            assertThat(fresh.getRatingCount()).isEqualTo(1);
        });
    }

    // --- helpers ---

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "rating-wire-it-" + UUID.randomUUID());
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
