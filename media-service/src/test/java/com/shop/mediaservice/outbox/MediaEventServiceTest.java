package com.shop.mediaservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.entity.MediaVariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MediaEventServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OutboxEventRepository outboxRepository;

    private MediaEventService service;

    private final UUID mediaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaEventService(outboxRepository, objectMapper);
    }

    private Media media() {
        Media media = Media.builder()
                .id(mediaId)
                .sha256("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
                .contentType("image/jpeg")
                .sizeBytes(48866)
                .build();
        MediaVariant originalJpeg = MediaVariant.builder()
                .variant("original").format("jpeg").width(2400).bytes(48866)
                .objectKey(mediaId + "/original.jpeg").build();
        MediaVariant thumbWebp = MediaVariant.builder()
                .variant("thumb").format("webp").width(320).bytes(4127)
                .objectKey(mediaId + "/thumb.webp").build();
        media.getVariants().addAll(List.of(originalJpeg, thumbWebp));
        return media;
    }

    @Test
    @DisplayName("MediaCreated payload pins EXACTLY the 7 D4 field names, verbatim")
    void record_created_pinsExactPayloadFields() throws Exception {
        Media media = media();

        service.record(media, MediaEventType.MediaCreated);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getAggregateType()).isEqualTo("media");
        // Spec D4: aggregateId = mediaId — it doubles as the Kafka partition
        // key (per-media partition ordering); the relay keys on it.
        assertThat(event.getAggregateId()).isEqualTo(mediaId);
        assertThat(event.getEventType()).isEqualTo("MediaCreated");
        assertThat(event.getTopic()).isEqualTo("media.lifecycle.v1");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getSentAt()).isNull();
        assertThat(event.getLastError()).isNull();

        // Spec D4 — exactly these 7 fields, verbatim names, nothing extra/missing.
        JsonNode json = objectMapper.readTree(event.getPayload());
        assertThat(json.size()).isEqualTo(7);
        assertThat(json.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("eventType", "mediaId", "sha256", "contentType",
                        "canonicalPath", "variants", "occurredAt");
        assertThat(json.get("eventType").textValue()).isEqualTo("MediaCreated");
        assertThat(UUID.fromString(json.get("mediaId").textValue())).isEqualTo(mediaId);
        assertThat(json.get("sha256").textValue())
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThat(json.get("contentType").textValue()).isEqualTo("image/jpeg");
        assertThat(json.get("canonicalPath").textValue()).isEqualTo("/api/v1/medias/" + mediaId);

        // variants: array of T2 MediaVariantResponse-shaped objects — exactly 5 fields each
        JsonNode variants = json.get("variants");
        assertThat(variants.isArray()).isTrue();
        assertThat(variants.size()).isEqualTo(2);
        JsonNode original = variants.get(0);
        assertThat(original.size()).isEqualTo(5);
        assertThat(original.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("variant", "format", "width", "bytes", "objectKey");
        assertThat(original.get("variant").textValue()).isEqualTo("original");
        assertThat(original.get("format").textValue()).isEqualTo("jpeg");
        assertThat(original.get("width").intValue()).isEqualTo(2400);
        assertThat(original.get("bytes").longValue()).isEqualTo(48866L);
        assertThat(original.get("objectKey").textValue()).isEqualTo(mediaId + "/original.jpeg");
        JsonNode thumb = variants.get(1);
        assertThat(thumb.get("variant").textValue()).isEqualTo("thumb");
        assertThat(thumb.get("format").textValue()).isEqualTo("webp");
        assertThat(thumb.get("width").intValue()).isEqualTo(320);
        assertThat(thumb.get("bytes").longValue()).isEqualTo(4127L);
        assertThat(thumb.get("objectKey").textValue()).isEqualTo(mediaId + "/thumb.webp");

        assertThat(Instant.parse(json.get("occurredAt").textValue())).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("MediaDeleted carries the same snapshot shape with eventType MediaDeleted")
    void record_deleted_sameShapeWithDeletedEventType() throws Exception {
        service.record(media(), MediaEventType.MediaDeleted);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getAggregateId()).isEqualTo(mediaId);
        assertThat(event.getEventType()).isEqualTo("MediaDeleted");
        assertThat(event.getTopic()).isEqualTo("media.lifecycle.v1");

        JsonNode json = objectMapper.readTree(event.getPayload());
        assertThat(json.size()).isEqualTo(7);
        assertThat(json.get("eventType").textValue()).isEqualTo("MediaDeleted");
        assertThat(UUID.fromString(json.get("mediaId").textValue())).isEqualTo(mediaId);
        assertThat(json.get("sha256").textValue())
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThat(json.get("canonicalPath").textValue()).isEqualTo("/api/v1/medias/" + mediaId);
        assertThat(json.get("variants").size()).isEqualTo(2);
        assertThat(Instant.parse(json.get("occurredAt").textValue())).isBeforeOrEqualTo(Instant.now());
    }
}
