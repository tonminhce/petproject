package com.shop.productservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.productservice.service.ProductMediaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

/**
 * Media lifecycle consumer (media epic spec D4, group product-service): on
 * {@code MediaDeleted} every live product referencing the media is cleared
 * and re-published as ProductUpdated (→ search doc refresh via the existing
 * chain). Other event types — e.g. the audit-only {@code MediaCreated} — are
 * ack-skipped.
 *
 * <p>T4 GATE: the relay publishes the payload STRING through the fleet
 * producer ({@code JsonKafkaSerializer}), so records arrive DOUBLE-ENCODED —
 * a JSON string token wrapping the event JSON. The raw value is therefore
 * parsed once and, when textual, unwrapped and parsed again; the shape check
 * also tolerates a future single-encoded relay. Malformed bytes (poison
 * records) are contained at parse time — never a listener throw.</p>
 *
 * <p>Ack-always poison posture (ProductRatingConsumer precedent): the
 * listener method must never throw — a handler failure is logged and
 * swallowed so the offset still advances. No DLT (fleet containment rule).</p>
 */
@Component
public class MediaDeletedConsumer extends BaseKafkaConsumer<String, String> {

    private final ProductMediaService productMediaService;
    private final ObjectMapper objectMapper;

    public MediaDeletedConsumer(ProductMediaService productMediaService, ObjectMapper objectMapper) {
        this.productMediaService = productMediaService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "media.lifecycle.v1", containerFactory = "mediaListenerFactory")
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
                case "MediaDeleted" -> productMediaService.clearReference(event.mediaId());
                default -> log.info("Skipping unknown media eventType {} (mediaId={})",
                    event.eventType(), event.mediaId());
            }
        } catch (Exception ex) {
            log.error("Failed to process media lifecycle payload", ex);
        }
    }

    private MediaLifecycleEvent decode(String rawValue) throws Exception {
        JsonNode node = objectMapper.readTree(rawValue);
        if (node.isTextual()) {
            // Double-encoded wire (T4): the fleet producer JSON-string-encoded
            // the payload — unwrap once before binding.
            node = objectMapper.readTree(node.textValue());
        }
        return objectMapper.treeToValue(node, MediaLifecycleEvent.class);
    }
}
