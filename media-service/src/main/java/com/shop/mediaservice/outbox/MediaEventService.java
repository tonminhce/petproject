package com.shop.mediaservice.outbox;

import com.shop.common.kafka.contract.KafkaTopicContract;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaEventService {

    static final String AGGREGATE_TYPE = "media";
    static final String TOPIC = KafkaTopicContract.MEDIA_LIFECYCLE;

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Media media, MediaEventType type) {
        OutboxEvent event = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType(AGGREGATE_TYPE)

                .aggregateId(media.getId())
                .eventType(type.name())
                .topic(TOPIC)
                .build();

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
