package com.shop.productservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.productservice.service.ProductMediaService;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MediaDeletedConsumer extends BaseKafkaConsumer<String, String> {

    static final int MAX_ATTEMPTS = 3;
    static final long BACKOFF_MILLIS = 200;

    private final ProductMediaService productMediaService;
    private final ObjectMapper objectMapper;

    public MediaDeletedConsumer(ProductMediaService productMediaService, ObjectMapper objectMapper) {
        this.productMediaService = productMediaService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicContract.MEDIA_LIFECYCLE, groupId = "product-service",
            containerFactory = "mediaListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {
        processMessage(rawValue, headers, this::handleContained);
    }

    private void handleContained(String rawValue) {
        try {
            MediaLifecycleEvent event = decode(rawValue);
            if (event == null || event.mediaId() == null) {
                log.info("Media event without mediaId — ack-skipping: {}", rawValue);
                return;
            }
            switch (event.eventType() == null ? "" : event.eventType()) {
                case "MediaDeleted" -> clearWithBoundedRetry(event.mediaId());
                default -> log.info("Skipping unknown media eventType {} (mediaId={})",
                    event.eventType(), event.mediaId());
            }
        } catch (Exception ex) {
            log.error("Failed to process media lifecycle payload", ex);
        }
    }

    private void clearWithBoundedRetry(UUID mediaId) {
        for (int attempt = 1; ; attempt++) {
            try {
                productMediaService.clearReference(mediaId);
                return;
            } catch (TransientDataAccessException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("Media-deleted clear FAILED for media {} after {} attempts — ack-skipping " +
                        "(reconciliation sweep is the backstop)", mediaId, attempt, ex);
                    return;
                }
                log.warn("Transient failure clearing media {} (attempt {}/{}), retrying in {}ms",
                    mediaId, attempt, MAX_ATTEMPTS, BACKOFF_MILLIS, ex);
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private MediaLifecycleEvent decode(String rawValue) throws Exception {
        JsonNode node = objectMapper.readTree(rawValue);
        if (node.isTextual()) {
            node = objectMapper.readTree(node.textValue());
        }
        return objectMapper.treeToValue(node, MediaLifecycleEvent.class);
    }
}
