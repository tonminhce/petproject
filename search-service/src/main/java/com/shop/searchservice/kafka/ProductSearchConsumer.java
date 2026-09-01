package com.shop.searchservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.searchservice.service.ProductSearchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

/**
 * Product lifecycle consumer (search spec D1 — primary consumer of
 * {@code shop.product.lifecycle.v1}). The fleet producer serializes the outbox
 * payload STRING via {@code JsonKafkaSerializer} (product OutboxRelay path),
 * so records arrive DOUBLE-ENCODED — a JSON string token wrapping the event
 * JSON (search spec §4.2 unwrap-once contract). The raw value is parsed once
 * and, when textual, unwrapped and parsed again before binding to
 * {@link ProductLifecycleEvent}; the shape check also tolerates a future
 * single-encoded relay. Malformed bytes (poison records) are contained at
 * parse time — never a listener throw.
 */
@Component
public class ProductSearchConsumer extends BaseKafkaConsumer<String, String> {

    private final ProductSearchService productSearchService;
    private final ObjectMapper objectMapper;

    public ProductSearchConsumer(ProductSearchService productSearchService, ObjectMapper objectMapper) {
        this.productSearchService = productSearchService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "shop.product.lifecycle.v1", containerFactory = "searchListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {
        processMessage(rawValue, headers, this::handleContained);
    }

    // Ack-always poison posture (product-service ProductRatingConsumer
    // precedent): the listener method must never throw — a handler failure is
    // logged and swallowed so the offset still advances. Unknown eventTypes
    // are ack-skipped (spec D1); no DLT (fleet containment rule).
    private void handleContained(String rawValue) {
        try {
            ProductLifecycleEvent event = decode(rawValue);
            switch (event.eventType() == null ? "" : event.eventType()) {
                case "ProductCreated", "ProductUpdated" -> productSearchService.index(event);
                case "ProductDeleted" -> productSearchService.delete(event.productId());
                default -> log.info("Skipping unknown product eventType {} (eventId={})",
                    event.eventType(), event.eventId());
            }
        } catch (Exception ex) {
            log.error("Failed to process product lifecycle payload", ex);
        }
    }

    private ProductLifecycleEvent decode(String rawValue) throws Exception {
        JsonNode node = objectMapper.readTree(rawValue);
        if (node.isTextual()) {
            // Double-encoded wire (D4/F-5): the fleet producer JSON-string-
            // encoded the payload — unwrap once before binding.
            node = objectMapper.readTree(node.textValue());
        }
        return objectMapper.treeToValue(node, ProductLifecycleEvent.class);
    }
}
