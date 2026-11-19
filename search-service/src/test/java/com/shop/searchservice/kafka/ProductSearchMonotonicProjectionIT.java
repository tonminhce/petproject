package com.shop.searchservice.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.searchservice.service.ProductSearchService;
import com.shop.searchservice.support.AbstractSearchIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ProductSearchMonotonicProjectionIT extends AbstractSearchIntegrationTest {

    private static final String TOPIC = "shop.product.lifecycle.v1";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    private static final String BRAND_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CATEGORY_ID = "22222222-2222-2222-2222-222222222222";

    private static final String NEWER_EVENT = "2026-08-31T12:00:00Z";
    private static final String OLDER_EVENT = "2026-08-31T11:00:00Z";

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
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class.getName());
        producerTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        kafkaMessagePublisher = new KafkaMessagePublisher(producerTemplate);
    }

    @AfterEach
    void closeProducer() {
        producerTemplate.destroy();
    }

    @Test
    @DisplayName("a product event updates the indexed document and stamps the external version")
    void eventUpdatesTheIndexedDocument() {
        UUID productId = UUID.randomUUID();

        send(productId, payload(productId, "ProductCreated", "ACTIVE", NEWER_EVENT, "25.50"));

        Map<String, Object> doc = awaitDocIndexed(productId);
        assertThat(doc)
            .containsEntry("id", productId.toString())
            .containsEntry("title", "Wireless Mouse")
            .containsEntry("status", "ACTIVE");
        assertThat(((Number) doc.get("price")).doubleValue()).isEqualTo(25.50d);
        assertThat(docVersion(productId)).isEqualTo(externalVersion(NEWER_EVENT));
    }

    @Test
    @DisplayName("an older event arriving after a newer one does not change the indexed document")
    void olderEventArrivingAfterANewerOneDoesNotChangeTheIndexedDocument() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> newer = payload(productId, "ProductUpdated", "ACTIVE", NEWER_EVENT, "39.99");
        newer.put("title", "Gaming Mouse");
        send(productId, newer);
        awaitTitle(productId, "Gaming Mouse");
        long versionBeforeStaleEvent = docVersion(productId);

        ListAppender<ILoggingEvent> rejections = captureRejectionLog();
        try {
            Map<String, Object> older = payload(productId, "ProductUpdated", "ACTIVE", OLDER_EVENT, "19.99");
            older.put("title", "Stale Mouse");
            send(productId, older);

            await().atMost(AWAIT).untilAsserted(() ->
                assertThat(rejections.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Stale product event for " + productId)));

            Map<String, Object> doc = indexedDoc(productId);
            assertThat(doc)
                .containsEntry("title", "Gaming Mouse")
                .containsEntry("status", "ACTIVE");
            assertThat(((Number) doc.get("price")).doubleValue()).isEqualTo(39.99d);
            assertThat(docVersion(productId)).isEqualTo(versionBeforeStaleEvent);
        } finally {
            detachRejectionLog(rejections);
        }
    }

    @Test
    @DisplayName("a redelivered event with the same version leaves the indexed document unchanged")
    void redeliveredEventWithTheSameVersionLeavesTheIndexUnchanged() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> payload = payload(productId, "ProductCreated", "ACTIVE", NEWER_EVENT, "25.50");
        send(productId, payload);
        Map<String, Object> indexed = awaitDocIndexed(productId);
        long versionBeforeRedelivery = docVersion(productId);

        ListAppender<ILoggingEvent> rejections = captureRejectionLog();
        try {
            send(productId, payload);

            await().atMost(AWAIT).untilAsserted(() ->
                assertThat(rejections.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Stale product event for " + productId)));

            assertThat(indexedDoc(productId)).isEqualTo(indexed);
            assertThat(docVersion(productId)).isEqualTo(versionBeforeRedelivery);
        } finally {
            detachRejectionLog(rejections);
        }
    }

    private Map<String, Object> payload(UUID productId, String eventType, String status,
                                       String occurredAt, String price) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("occurredAt", occurredAt);
        payload.put("productId", productId.toString());
        payload.put("slug", "wireless-mouse-" + productId);
        payload.put("status", status);
        payload.put("title", "Wireless Mouse");
        payload.put("description", "Ergonomic wireless mouse");
        payload.put("brandId", BRAND_ID);
        payload.put("brandName", "Logitech");
        payload.put("categoryId", CATEGORY_ID);
        payload.put("categoryName", "Accessories");
        payload.put("price", new BigDecimal(price));
        payload.put("imageUrl", "http://img.example/mouse.png");
        payload.put("avgRating", new BigDecimal("4.50"));
        payload.put("ratingCount", 2);
        payload.put("updatedAt", occurredAt);
        return payload;
    }

    private void send(UUID productId, Map<String, Object> payload) {
        try {
            kafkaMessagePublisher.publish(TOPIC, productId.toString(),
                objectMapper.writeValueAsString(payload));
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

    private long docVersion(UUID productId) {
        try {
            client.indices().refresh(r -> r.index("products"));
            var response = client.get(g -> g.index("products").id(productId.toString()), Map.class);
            return response.version();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long externalVersion(String timestamp) {
        return Instant.parse(timestamp).getEpochSecond() * 1_000_000_000L + Instant.parse(timestamp).getNano();
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

    private void awaitTitle(UUID productId, String title) {
        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(indexedDoc(productId)).containsEntry("title", title));
    }

    private ListAppender<ILoggingEvent> captureRejectionLog() {
        var logger = (Logger) LoggerFactory.getLogger(ProductSearchService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        return events;
    }

    private void detachRejectionLog(ListAppender<ILoggingEvent> events) {
        var logger = (Logger) LoggerFactory.getLogger(ProductSearchService.class);
        logger.detachAppender(events);
        events.stop();
    }
}
