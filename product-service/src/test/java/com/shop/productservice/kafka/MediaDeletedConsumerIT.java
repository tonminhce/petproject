package com.shop.productservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * MediaDeleted consumer against the REAL Kafka wire (media epic spec D4):
 * the fleet relay publishes the outbox payload STRING through the
 * JsonKafkaSerializer producer, so records arrive DOUBLE-ENCODED (T4 gate —
 * this IT publishes through the SAME context KafkaTemplate, reproducing the
 * exact wire shape of MediaOutboxRelay). On MediaDeleted the product's
 * media_id is cleared, the derived image falls back to the legacy imageUrl,
 * and a ProductUpdated outbox row (→ search refresh) is emitted per product.
 * Unknown eventTypes and poison bytes must not stall the partition.
 */
class MediaDeletedConsumerIT extends AbstractIntegrationTest {

    private static final String TOPIC = "media.lifecycle.v1";

    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * T1 review carry-over: this IT ran on the unpinned application.yml
     * default ({@code latest}) — a record published around container-join
     * time was silently skipped (the exact class of bug this epic hunts).
     * Pin {@code earliest} like {@link RatingLifecycleConsumerIT} so the
     * listener consumes deterministically from offset 0 of the fresh
     * container topic; prod keeps {@code latest}.
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

    private Product productWithMedia(UUID mediaId, String slugPrefix) {
        Product p = Product.builder()
            .title(slugPrefix)
            .slug(slugPrefix + "-" + UUID.randomUUID())
            .sku(slugPrefix.substring(0, 3) + "-" + UUID.randomUUID().toString().substring(0, 8))
            .priceUnit(new BigDecimal("999.00"))
            .quantity(10)
            .status(ProductStatus.ACTIVE)
            .imageUrl("http://legacy.example/ip15.png")
            .mediaId(mediaId)
            .build();
        productRepository.save(p);
        outboxRepository.deleteAllInBatch(); // isolate this test's ProductUpdated assertions
        return p;
    }

    /** Publishes EXACTLY like MediaOutboxRelay: payload String via the fleet KafkaTemplate. */
    private void publishExactlyLikeRelay(String payloadJson, UUID key) {
        kafkaTemplate.send(TOPIC, key.toString(), payloadJson).join();
    }

    private String mediaPayload(String eventType, UUID mediaId) {
        var node = objectMapper.createObjectNode()
            .put("eventType", eventType)
            .put("mediaId", mediaId.toString())
            .put("sha256", "deadbeef")
            .put("contentType", "image/jpeg")
            .put("canonicalPath", "/api/v1/medias/" + mediaId)
            .put("occurredAt", "2026-09-01T10:00:00Z");
        return writeJson(node);
    }

    private String writeJson(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("double-encoded MediaDeleted clears media_id and emits one ProductUpdated per product")
    void doubleEncodedMediaDeleted_clearsReferenceAndEmitsProductUpdated() {
        Product product = productWithMedia(MEDIA_ID, "iPhone 15");
        publishExactlyLikeRelay(mediaPayload("MediaDeleted", MEDIA_ID), MEDIA_ID);

        AtomicReference<OutboxEvent> updated = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Product fresh = productRepository.findById(product.getId()).orElseThrow();
            assertThat(fresh.getMediaId()).as("media_id must be cleared").isNull();
            List<OutboxEvent> rows = outboxRepository.findAll().stream()
                .filter(r -> "ProductUpdated".equals(r.getEventType()))
                .toList();
            assertThat(rows).as("one ProductUpdated outbox row per cleared product").hasSize(1);
            updated.set(rows.get(0));
        });

        OutboxEvent row = updated.get();
        assertThat(row.getAggregateId()).isEqualTo(product.getId());
        assertThat(row.getTopic()).isEqualTo("shop.product.lifecycle.v1");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        JsonNode snapshot = readPayload(row.getPayload());
        // D5: the payload imageUrl falls back to the legacy free-string once
        // the reference is gone — the derived canonical path belonged to the
        // deleted media.
        assertThat(snapshot.get("imageUrl").asText()).isEqualTo("http://legacy.example/ip15.png");
        assertThat(snapshot.get("slug").asText()).isEqualTo(product.getSlug());
    }

    @Test
    @DisplayName("real kafka record value is a JSON string token wrapping the event (T4 wire shape)")
    void wireShapeIsDoubleEncodedOnTheRealBroker() throws Exception {
        productWithMedia(MEDIA_ID, "iPhone 15");
        publishExactlyLikeRelay(mediaPayload("MediaDeleted", MEDIA_ID), MEDIA_ID);

        ConsumerRecord<String, String> record = awaitRecord(TOPIC, MEDIA_ID.toString());

        assertThat(record).as("record on media.lifecycle.v1 with key=mediaId").isNotNull();
        JsonNode node = objectMapper.readTree(record.value());
        // The fleet serializer string-encodes the payload String — the raw wire
        // value is a STRING TOKEN, not the event object itself.
        assertThat(node.isTextual())
            .as("T4 gate: wire value must be double-encoded (JSON string token)")
            .isTrue();
        JsonNode unwrapped = objectMapper.readTree(node.textValue());
        assertThat(unwrapped.get("eventType").textValue()).isEqualTo("MediaDeleted");
        assertThat(unwrapped.get("mediaId").textValue()).isEqualTo(MEDIA_ID.toString());
    }

    @Test
    @DisplayName("unknown eventType (MediaCreated) is ack-skipped: no clear, partition keeps advancing")
    void mediaCreatedEventTypeIsAckSkipped() {
        Product untouched = productWithMedia(MEDIA_ID, "Kept Product");
        publishExactlyLikeRelay(mediaPayload("MediaCreated", MEDIA_ID), MEDIA_ID);

        UUID markerMedia = UUID.randomUUID();
        Product marker = productWithMedia(markerMedia, "Marker Product");
        publishExactlyLikeRelay(mediaPayload("MediaDeleted", markerMedia), markerMedia);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(productRepository.findById(marker.getId()).orElseThrow().getMediaId())
                .as("marker cleared — partition advanced past the MediaCreated record").isNull());

        Product fresh = productRepository.findById(untouched.getId()).orElseThrow();
        assertThat(fresh.getMediaId()).as("MediaCreated must not clear references").isEqualTo(MEDIA_ID);
    }

    @Test
    @DisplayName("poison bytes never stall the partition — a later MediaDeleted still clears")
    void poisonBytesDoNotStallPartition() {
        Product product = productWithMedia(MEDIA_ID, "Poison Product");
        publishExactlyLikeRelay("{\"broken\"", MEDIA_ID);

        publishExactlyLikeRelay(mediaPayload("MediaDeleted", MEDIA_ID), MEDIA_ID);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(productRepository.findById(product.getId()).orElseThrow().getMediaId()).isNull());
    }

    // --- helpers ---

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "media-wire-it-" + UUID.randomUUID());
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
