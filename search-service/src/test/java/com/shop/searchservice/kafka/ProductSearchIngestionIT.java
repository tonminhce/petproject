package com.shop.searchservice.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.searchservice.support.AbstractSearchIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

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
 * End-to-end ingestion contract over real Kafka + ES containers: records are
 * produced through the SAME path production uses — {@link KafkaMessagePublisher}
 * over a StringSerializer ×2 template (the {@code stringKafkaTemplate}
 * semantics of product's {@code OutboxRelay}, R1) — so the payload String
 * lands SINGLE-ENCODED UTF-8 JSON on the wire. The live
 * {@code search-service} group binds single-encoded events directly and must
 * unwrap-once (spec §4.2) the LEGACY double-encoded shape (a JSON string
 * token wrapping the event JSON) that pre-R1 in-flight records may still
 * carry — pinned by the legacy-wire test. The helper previously used a
 * {@code JsonKafkaSerializer} double-encoding template — a wire production
 * no longer emits. Poison records must never stall the partition — each
 * negative case proves progression via a marker product event sent after.
 */
class ProductSearchIngestionIT extends AbstractSearchIntegrationTest {

    private static final String TOPIC = "shop.product.lifecycle.v1";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    private static final String BRAND_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CATEGORY_ID = "22222222-2222-2222-2222-222222222222";

    @Autowired
    private ElasticsearchClient client;

    @Value("${shop.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTemplate<String, String> producerTemplate;
    private KafkaMessagePublisher kafkaMessagePublisher;

    @BeforeEach
    void initProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // The R1 production path: KafkaMessagePublisher over StringSerializer
        // ×2 (stringKafkaTemplate semantics) — the payload String is forwarded
        // as raw UTF-8 bytes, single-encoded JSON on the wire.
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        kafkaMessagePublisher = new KafkaMessagePublisher(producerTemplate);
    }

    @AfterEach
    void closeProducer() {
        producerTemplate.destroy();
    }

    @Test
    @DisplayName("ProductCreated indexes the full-snapshot doc with every D3 field")
    void productCreatedEventIndexesFullSnapshotDoc() {
        UUID productId = UUID.randomUUID();

        send(productId, payload(productId, "ProductCreated", "ACTIVE"));

        Map<String, Object> doc = awaitDocIndexed(productId);
        assertThat(doc)
            .containsEntry("id", productId.toString())
            .containsEntry("title", "Wireless Mouse")
            .containsEntry("description", "Ergonomic wireless mouse")
            .containsEntry("brandId", BRAND_ID)
            .containsEntry("brandName", "Logitech")
            .containsEntry("categoryId", CATEGORY_ID)
            .containsEntry("categoryName", "Accessories")
            .containsEntry("slug", "wireless-mouse-" + productId)
            .containsEntry("imageUrl", "http://img.example/mouse.png")
            .containsEntry("status", "ACTIVE")
            .containsEntry("updatedAt", "2026-08-31T10:15:30Z");
        assertThat(((Number) doc.get("price")).doubleValue()).isEqualTo(25.50d);
        assertThat(((Number) doc.get("avgRating")).doubleValue()).isEqualTo(4.50d);
        assertThat(((Number) doc.get("ratingCount")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("LEGACY double-encoded fleet token (pre-R1 in-flight shape) is unwrapped and indexed (F-5, H-1)")
    void legacyDoubleEncodedTokenIsAccepted() throws Exception {
        UUID productId = UUID.randomUUID();

        sendLegacyDoubleEncoded(productId, payload(productId, "ProductCreated", "ACTIVE"));

        // Wire proof on the real topic: the legacy record value is a JSON
        // string token wrapping the event JSON — the pre-R1 wire that
        // in-flight records may still carry.
        ConsumerRecord<String, String> record = awaitRecord(productId);
        assertThat(record).as("record on %s", TOPIC).isNotNull();
        JsonNode token = objectMapper.readTree(record.value());
        assertThat(token.isTextual()).as("legacy wire shape is a JSON string token").isTrue();
        JsonNode unwrapped = objectMapper.readTree(token.textValue());
        assertThat(unwrapped.get("eventType").textValue()).isEqualTo("ProductCreated");
        assertThat(unwrapped.get("productId").textValue()).isEqualTo(productId.toString());

        // ...and the legacy double-encoded record is actually ingested.
        Map<String, Object> doc = awaitDocIndexed(productId);
        assertThat(doc)
            .containsEntry("id", productId.toString())
            .containsEntry("status", "ACTIVE");
    }

    @Test
    @DisplayName("ProductUpdated replaces the stored doc fields")
    void productUpdatedEventReplacesDoc() {
        UUID productId = UUID.randomUUID();
        send(productId, payload(productId, "ProductCreated", "ACTIVE"));
        awaitDocIndexed(productId);

        Map<String, Object> updated = payload(productId, "ProductUpdated", "ACTIVE");
        updated.put("title", "Gaming Mouse");
        updated.put("price", new BigDecimal("39.99"));
        updated.put("updatedAt", "2026-08-31T11:30:00Z");
        send(productId, updated);

        AtomicReference<Map<String, Object>> doc = new AtomicReference<>();
        await().atMost(AWAIT).untilAsserted(() -> {
            var current = indexedDoc(productId);
            assertThat(current).isNotNull();
            assertThat(current.get("title")).isEqualTo("Gaming Mouse");
            doc.set(current);
        });
        assertThat(((Number) doc.get().get("price")).doubleValue()).isEqualTo(39.99d);
        assertThat(doc.get().get("updatedAt")).isEqualTo("2026-08-31T11:30:00Z");
    }

    @Test
    @DisplayName("ACTIVE to non-ACTIVE transition deletes the doc (F1)")
    void activeToNonActiveTransitionDeletesDoc() {
        UUID productId = UUID.randomUUID();
        send(productId, payload(productId, "ProductCreated", "ACTIVE"));
        awaitDocIndexed(productId);

        send(productId, payload(productId, "ProductUpdated", "ARCHIVED"));

        awaitDocDeleted(productId);
    }

    @Test
    @DisplayName("non-ACTIVE re-published as ACTIVE upserts the doc, null rating tolerated (F1)")
    void nonActiveToActiveRepublishUpsertsDoc() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> archived = payload(productId, "ProductUpdated", "ARCHIVED");
        archived.put("avgRating", null);
        archived.put("ratingCount", null);
        send(productId, archived);
        awaitDocDeleted(productId);

        Map<String, Object> active = payload(productId, "ProductUpdated", "ACTIVE");
        active.put("avgRating", null);
        active.put("ratingCount", null);
        send(productId, active);

        Map<String, Object> doc = awaitDocIndexed(productId);
        assertThat(doc)
            .containsEntry("id", productId.toString())
            .containsEntry("status", "ACTIVE");
        assertThat(doc.get("avgRating")).isNull();
        assertThat(doc.get("ratingCount")).isNull();
    }

    @Test
    @DisplayName("ProductDeleted removes the doc")
    void productDeletedEventDeletesDoc() {
        UUID productId = UUID.randomUUID();
        send(productId, payload(productId, "ProductCreated", "ACTIVE"));
        awaitDocIndexed(productId);

        send(productId, payload(productId, "ProductDeleted", "ACTIVE"));

        awaitDocDeleted(productId);
    }

    @Test
    @DisplayName("ProductDeleted for a never-indexed product is a no-op and consumption continues")
    void deleteOfNeverIndexedProductIsNoOp() {
        UUID neverIndexed = UUID.randomUUID();

        send(neverIndexed, payload(neverIndexed, "ProductDeleted", "ACTIVE"));

        UUID marker = UUID.randomUUID();
        send(marker, payload(marker, "ProductCreated", "ACTIVE"));
        awaitDocIndexed(marker);
        awaitDocDeleted(neverIndexed);
    }

    @Test
    @DisplayName("unknown eventType is ack-skipped: no doc, consumption continues")
    void unknownEventTypeIsAckSkipped() {
        UUID unknown = UUID.randomUUID();

        send(unknown, payload(unknown, "ProductFeatured.v9", "ACTIVE"));

        assertThat(indexedDoc(unknown)).isNull();
        UUID marker = UUID.randomUUID();
        send(marker, payload(marker, "ProductCreated", "ACTIVE"));
        awaitDocIndexed(marker);
        assertThat(indexedDoc(unknown)).isNull();
    }

    @Test
    @DisplayName("null eventType is ack-skipped at INFO: no doc, no handler failure, consumption continues")
    void nullEventTypeIsAckSkipped() {
        var consumerLogger = (Logger) LoggerFactory.getLogger(ProductSearchConsumer.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        consumerLogger.addAppender(events);
        try {
            UUID nullType = UUID.randomUUID();
            Map<String, Object> noEventType = payload(nullType, "ProductCreated", "ACTIVE");
            noEventType.remove("eventType");
            send(nullType, noEventType);

            UUID marker = UUID.randomUUID();
            send(marker, payload(marker, "ProductCreated", "ACTIVE"));
            awaitDocIndexed(marker);

            assertThat(indexedDoc(nullType)).isNull();
            assertThat(events.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .startsWith("Skipping unknown product eventType null"))
                .noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        } finally {
            consumerLogger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    @DisplayName("malformed JSON is consumed without throwing and the partition keeps advancing")
    void malformedPayloadIsConsumedNoThrow() {
        sendRaw("{\\\"eventId\\\": \\\"poison\\\"");

        UUID marker = UUID.randomUUID();
        send(marker, payload(marker, "ProductCreated", "ACTIVE"));
        awaitDocIndexed(marker);
    }

    private Map<String, Object> payload(UUID productId, String eventType, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("occurredAt", "2026-08-31T10:00:00Z");
        payload.put("productId", productId.toString());
        payload.put("slug", "wireless-mouse-" + productId);
        payload.put("status", status);
        payload.put("title", "Wireless Mouse");
        payload.put("description", "Ergonomic wireless mouse");
        payload.put("brandId", BRAND_ID);
        payload.put("brandName", "Logitech");
        payload.put("categoryId", CATEGORY_ID);
        payload.put("categoryName", "Accessories");
        payload.put("price", new BigDecimal("25.50"));
        payload.put("imageUrl", "http://img.example/mouse.png");
        payload.put("avgRating", new BigDecimal("4.50"));
        payload.put("ratingCount", 2);
        payload.put("updatedAt", "2026-08-31T10:15:30Z");
        return payload;
    }

    /** Publishes through the R1 production path: single-encoded JSON on the wire. */
    private void send(UUID productId, Map<String, Object> payload) {
        try {
            kafkaMessagePublisher.publish(TOPIC, productId.toString(),
                objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Publishes the pre-R1 legacy wire: a JSON string token wrapping the event
     * JSON (H-1 tolerance pin for in-flight records from pre-R1 producers).
     */
    private void sendLegacyDoubleEncoded(UUID productId, Map<String, Object> payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            kafkaMessagePublisher.publish(TOPIC, productId.toString(),
                objectMapper.writeValueAsString(payloadJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(String json) {
        try {
            kafkaMessagePublisher.publish(TOPIC, UUID.randomUUID().toString(), json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> indexedDoc(UUID productId) {
        try {
            client.indices().refresh(r -> r.index("products"));
            var response = client.get(g -> g.index("products").id(productId.toString()), Map.class);
            return response.found() ? response.source() : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raw readback (StringDeserializer) to prove the actual wire shape. */
    private ConsumerRecord<String, String> awaitRecord(UUID key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "search-ingestion-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private Map<String, Object> awaitDocIndexed(UUID productId) {
        AtomicReference<Map<String, Object>> doc = new AtomicReference<>();
        await().atMost(AWAIT).untilAsserted(() -> {
            var current = indexedDoc(productId);
            assertThat(current).as("doc %s indexed", productId).isNotNull();
            doc.set(current);
        });
        return doc.get();
    }

    private void awaitDocDeleted(UUID productId) {
        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(indexedDoc(productId)).as("doc %s deleted", productId).isNull());
    }
}
