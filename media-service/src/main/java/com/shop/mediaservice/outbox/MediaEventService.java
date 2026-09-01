package com.shop.mediaservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.mediaservice.dto.response.MediaVariantResponse;
import com.shop.mediaservice.entity.Media;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the media lifecycle outbox row (spec D4) in the SAME transaction as
 * the media write — the relay publishes it later on topic
 * {@code media.lifecycle.v1}. The payload carries the FULL media snapshot
 * (snapshot-carry precedent) so consumers stay dumb, idempotent copy-readers:
 * the product-service D4 consumer clears {@code products.media_id} from the
 * {@code mediaId} alone, audit/CDN hooks get sha256/contentType/variants for
 * free.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaEventService {

    static final String AGGREGATE_TYPE = "media";
    static final String TOPIC = "media.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Joins the caller's transaction (REQUIRED): the row must commit or roll
     * back with the media write it describes. The upload pipeline calls this
     * INSIDE its final DB transaction (after the S3 writes, with the media
     * row); the lifecycle soft-delete calls it in the same transaction as the
     * {@code deleted_at} update.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Media media, MediaEventType type) {
        OutboxEvent event = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType(AGGREGATE_TYPE)
                // Spec D4: Kafka message key = mediaId (per-media partition
                // ordering) and the relay keys on aggregateId — the partition
                // key lives here, not the outbox row id. Media identity still
                // travels in payload.mediaId and eventId.
                .aggregateId(media.getId())
                .eventType(type.name())
                .topic(TOPIC)
                .build();

        // Field order + names are contract (spec D4) — LinkedHashMap, 7 fields.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", type.name());
        payload.put("mediaId", media.getId());
        payload.put("sha256", media.getSha256());
        payload.put("contentType", media.getContentType());
        payload.put("canonicalPath", ApiPaths.MEDIAS + "/" + media.getId());
        payload.put("variants", media.getVariants().stream()
                .map(v -> new MediaVariantResponse(v.getVariant(), v.getFormat(), v.getWidth(),
                        v.getBytes(), v.getObjectKey()))
                .toList());
        payload.put("occurredAt", Instant.now().toString());

        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox payload for media {}", media.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }
}
