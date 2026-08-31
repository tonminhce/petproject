package com.shop.searchservice.kafka;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.shop.searchservice.support.AbstractSearchIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end ingestion contract over real Kafka + ES containers: JSON-string
 * records produced to {@code shop.product.lifecycle.v1} (fleet wire format)
 * are consumed by the live {@code search-service} group and mirrored as
 * upsert/delete operations on the {@code products} alias (spec D1, F1
 * bidirectional status). Poison records must never stall the partition —
 * each negative case proves progression via a marker product event sent after.
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
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void initProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @AfterEach
    void closeProducer() {
        kafkaTemplate.destroy();
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

    private void send(UUID productId, Map<String, Object> payload) {
        try {
            kafkaTemplate.send(TOPIC, productId.toString(), objectMapper.writeValueAsString(payload))
                .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(String json) {
        try {
            kafkaTemplate.send(TOPIC, UUID.randomUUID().toString(), json).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
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
