package com.shop.mediaservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.kafka.exception.KafkaPublishException;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaUploadService;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import com.shop.mediaservice.support.TestImages;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D4 same-transaction + relay contract against the real stack: the
 * MediaCreated outbox row commits INSIDE the media-row transaction (last, after
 * the S3 writes), the MediaDeleted row commits INSIDE the deleted_at-update
 * transaction, the relay publishes key=mediaId onto media.lifecycle.v1 with the
 * FULL snapshot payload, and a publisher outage leaves rows unsent for the next
 * cycle (no DLT).
 */
class MediaOutboxIT extends AbstractMediaIntegrationTest {

    @Autowired
    private MediaUploadService uploadService;

    @Autowired
    private MediaLifecycleService lifecycleService;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private MediaOutboxRelay relay;

    @Autowired
    private ObjectMapper objectMapper;

    /** Relay's scheduled poll must not race the manual {@code relay()} calls in tests. */
    @DynamicPropertySource
    static void relayProps(DynamicPropertyRegistry registry) {
        registry.add("shop.media.outbox.poll-millis", () -> "3600000");
        // low bar so the FAILED parking (and its replay) is reachable in-test
        registry.add("shop.media.outbox.max-retries", () -> "2");
    }

    @BeforeEach
    void resetState() {
        outboxRepository.deleteAllInBatch();
        mediaRepository.deleteAllInBatch();
        FlakyOutbox.failSave = false;
        FlakyPublisher.failing = false;
    }

    // --- same-tx: upload commit path ---

    @Test
    @DisplayName("upload commits MediaCreated outbox row in the SAME transaction as the media row")
    void uploadWritesMediaCreatedRowWithFullSnapshot() throws Exception {
        byte[] source = TestImages.jpeg(1024, 768);

        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));

        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("media");
        assertThat(row.getAggregateId()).isEqualTo(response.id());
        assertThat(row.getEventType()).isEqualTo("MediaCreated");
        assertThat(row.getTopic()).isEqualTo("media.lifecycle.v1");
        assertThat(row.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.PENDING);
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getSentAt()).isNull();

        JsonNode json = objectMapper.readTree(row.getPayload());
        assertThat(json.size()).isEqualTo(7);
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventType", "mediaId", "sha256", "contentType",
                        "canonicalPath", "variants", "occurredAt");
        assertThat(json.get("eventType").textValue()).isEqualTo("MediaCreated");
        assertThat(json.get("mediaId").textValue()).isEqualTo(response.id().toString());
        assertThat(json.get("sha256").textValue()).isEqualTo(sha256Hex(source));
        assertThat(json.get("contentType").textValue()).isEqualTo("image/jpeg");
        assertThat(json.get("canonicalPath").textValue())
                .isEqualTo("/api/v1/medias/" + response.id());
        assertThat(json.get("variants").isArray()).isTrue();
        assertThat(json.get("variants").size()).isEqualTo(6); // 3 variants × original-format + webp
        for (JsonNode variant : json.get("variants")) {
            assertThat(variant.size()).isEqualTo(5);
            assertThat(variant.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("variant", "format", "width", "bytes", "objectKey");
        }
    }

    @Test
    @DisplayName("duplicate upload resolves to existing media — no second outbox row")
    void duplicateUploadWritesNoSecondRow() {
        byte[] source = TestImages.jpeg(640, 480);

        uploadService.upload(multipart("image/jpeg", source));
        uploadService.upload(multipart("image/jpeg", source));

        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejected upload writes no outbox row")
    void rejectedUploadWritesNoRow() {
        assertThatThrownBy(() -> uploadService.upload(multipart("image/gif", TestImages.nonImageBytes())))
                .isInstanceOf(BusinessException.class);

        assertThat(outboxRepository.count()).isZero();
        assertThat(mediaRepository.count()).isZero();
    }

    // --- same-tx: soft-delete commit path ---

    @Test
    @DisplayName("soft-delete commits MediaDeleted row in the SAME transaction as the deleted_at update")
    void softDeleteWritesMediaDeletedRowWithSnapshot() throws Exception {
        byte[] source = TestImages.jpeg(640, 480);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));

        lifecycleService.softDelete(response.id());

        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(2);
        OutboxEvent deletedRow = rows.stream()
                .filter(r -> "MediaDeleted".equals(r.getEventType()))
                .findFirst().orElseThrow();
        assertThat(deletedRow.getAggregateId()).isEqualTo(response.id());
        assertThat(deletedRow.getTopic()).isEqualTo("media.lifecycle.v1");

        JsonNode json = objectMapper.readTree(deletedRow.getPayload());
        assertThat(json.size()).isEqualTo(7);
        assertThat(json.get("eventType").textValue()).isEqualTo("MediaDeleted");
        assertThat(json.get("mediaId").textValue()).isEqualTo(response.id().toString());
        assertThat(json.get("sha256").textValue()).isEqualTo(sha256Hex(source));
        assertThat(json.get("canonicalPath").textValue())
                .isEqualTo("/api/v1/medias/" + response.id());
        assertThat(json.get("variants").size()).isEqualTo(6);
    }

    @Test
    @DisplayName("repeat delete → 409 MED-12005 and NO second MediaDeleted row")
    void repeatDelete_conflict_writesNoSecondRow() {
        byte[] source = TestImages.jpeg(640, 480);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));

        lifecycleService.softDelete(response.id());
        assertThatThrownBy(() -> lifecycleService.softDelete(response.id()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12005");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });

        long deletedRows = outboxRepository.findAll().stream()
                .filter(r -> "MediaDeleted".equals(r.getEventType()))
                .count();
        assertThat(deletedRows).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(2); // MediaCreated + MediaDeleted, no more
    }

    // --- same-tx rollback proof: outbox insert failure rolls the media write back ---

    @Test
    @DisplayName("outbox insert fails on upload → media row rolls back, orphan objects purged")
    void outboxInsertFailureRollsBackMediaRow() {
        byte[] source = TestImages.jpeg(640, 480);
        FlakyOutbox.failSave = true;

        assertThatThrownBy(() -> uploadService.upload(multipart("image/jpeg", source)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(mediaRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("outbox insert fails on soft-delete → deleted_at update rolls back (row stays live)")
    void outboxInsertFailureRollsBackSoftDelete() {
        byte[] source = TestImages.jpeg(640, 480);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));

        FlakyOutbox.failSave = true;
        assertThatThrownBy(() -> lifecycleService.softDelete(response.id()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(mediaRepository.findById(response.id())).isPresent(); // not deleted
        assertThat(outboxRepository.count()).isEqualTo(1); // only the MediaCreated row
    }

    // --- relay → real Kafka ---

    @Test
    @DisplayName("relay publishes to media.lifecycle.v1 with key=mediaId and the snapshot payload; sent flag flips")
    void relayPublishesToKafkaWithMediaIdKey() throws Exception {
        byte[] source = TestImages.jpeg(640, 480);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));
        OutboxEvent row = outboxRepository.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.PENDING);

        relay.relay();

        OutboxEvent after = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();

        ConsumerRecord<String, String> record = awaitRecord("media.lifecycle.v1", response.id().toString());
        assertThat(record).as("record on media.lifecycle.v1 with key=mediaId").isNotNull();
        JsonNode json = snapshot(record.value());
        assertThat(json.get("eventType").textValue()).isEqualTo("MediaCreated");
        assertThat(json.get("mediaId").textValue()).isEqualTo(response.id().toString());
        assertThat(json.get("sha256").textValue()).isEqualTo(sha256Hex(source));
        assertThat(json.get("canonicalPath").textValue())
                .isEqualTo("/api/v1/medias/" + response.id());
        assertThat(json.get("variants").size()).isEqualTo(6);
    }

    @Test
    @DisplayName("publisher outage → row stays PENDING with retryCount+1; next cycle publishes")
    void relayFailureRowStaysPendingThenRetries() throws Exception {
        byte[] source = TestImages.jpeg(640, 480);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));
        OutboxEvent row = outboxRepository.findAll().get(0);

        FlakyPublisher.failing = true;
        relay.relay();

        OutboxEvent failedCycle = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(failedCycle.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.PENDING);
        assertThat(failedCycle.getRetryCount()).isEqualTo(1);
        assertThat(failedCycle.getLastError()).isNotBlank();

        FlakyPublisher.failing = false;
        relay.relay();

        OutboxEvent retried = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.SENT);
        assertThat(retried.getRetryCount()).isEqualTo(1); // unchanged by the successful publish
        assertThat(awaitRecord("media.lifecycle.v1", response.id().toString())).isNotNull();
    }

    @Test
    @DisplayName("FAILED row is REPLAYED: outage past max-retries parks the row, next healthy cycle publishes + SENT")
    void relayFailedRowIsReplayedOnNextCycle() throws Exception {
        byte[] source = TestImages.jpeg(640, 480);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));
        OutboxEvent row = outboxRepository.findAll().get(0);

        FlakyPublisher.failing = true;
        relay.relay(); // retry 1/2
        relay.relay(); // retry 2/2 → parked FAILED
        OutboxEvent parked = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.FAILED);
        assertThat(parked.getLastError()).isNotBlank();

        FlakyPublisher.failing = false;
        relay.relay(); // replay cycle picks FAILED rows up again

        OutboxEvent replayed = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(replayed.getStatus()).isEqualTo(com.shop.common.core.constants.OutboxStatus.SENT);
        assertThat(replayed.getSentAt()).isNotNull();
        assertThat(awaitRecord("media.lifecycle.v1", response.id().toString())).isNotNull();
    }

    // --- helpers ---

    private org.springframework.web.multipart.MultipartFile multipart(String contentType, byte[] bytes) {
        return new org.springframework.mock.web.MockMultipartFile("file", "upload", contentType, bytes);
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** The fleet serializer string-encodes the payload String — unwrap one token if present. */
    private JsonNode snapshot(String rawValue) throws Exception {
        JsonNode node = objectMapper.readTree(rawValue);
        if (node.isTextual()) {
            node = objectMapper.readTree(node.textValue());
        }
        return node;
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "media-outbox-it-" + UUID.randomUUID());
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

    /** Test-only outbox wrapper: throws on the next save while the flag is set. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyOutboxConfig {

        @Bean
        @Primary
        OutboxEventRepository flakyOutboxRepository(OutboxEventRepository real) {
            return (OutboxEventRepository) Proxy.newProxyInstance(
                    OutboxEventRepository.class.getClassLoader(),
                    new Class<?>[]{OutboxEventRepository.class},
                    (proxy, method, args) -> {
                        if (FlakyOutbox.failSave && method.getName().equals("save")) {
                            throw new IllegalStateException("Injected outbox write failure (test)");
                        }
                        return method.invoke(real, args);
                    });
        }
    }

    static final class FlakyOutbox {
        static volatile boolean failSave = false;
    }

    /** Test-only publisher wrapper: outage while the flag is set, pass-through otherwise. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyKafkaConfig {

        @Bean
        @Primary
        KafkaMessagePublisher flakyPublisher(KafkaTemplate<String, String> template) {
            return new FlakyPublisher(template);
        }
    }

    static final class FlakyPublisher extends KafkaMessagePublisher {

        static volatile boolean failing = false;

        FlakyPublisher(KafkaTemplate<String, String> template) {
            super(template);
        }

        @Override
        public void publish(String topic, String key, Object value) {
            if (failing) {
                throw new KafkaPublishException("Injected kafka outage (test)");
            }
            super.publish(topic, key, value);
        }
    }
}
