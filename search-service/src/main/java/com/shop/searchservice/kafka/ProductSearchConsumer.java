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
 * {@code shop.product.lifecycle.v1}). Wire contract (R1 + H-1): producers
 * publish JSON-as-String via {@code KafkaMessagePublisher} (product
 * OutboxRelay path), so records arrive SINGLE-ENCODED UTF-8 JSON (search spec
 * §4.2 unwrap-once contract). The raw value is parsed once and, when textual
 * (a LEGACY double-encoded token — a JSON string token wrapping the event
 * JSON, from pre-R1 producers), unwrapped and parsed again before binding to
 * {@link ProductLifecycleEvent}; both shapes bind. Malformed bytes (poison
 * records) are contained at parse time — never a listener throw.
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
        ProductLifecycleEvent event;
        try {
            event = decode(rawValue);
        } catch (Exception ex) {
            log.error("Failed to decode product lifecycle payload — ack-skipping: {}", rawValue, ex);
            return;
        }
        if (event == null) {
            return;
        }
        switch (event.eventType() == null ? "" : event.eventType()) {
            case "ProductCreated", "ProductUpdated" -> productSearchService.index(event);
            case "ProductDeleted" -> productSearchService.delete(event.productId());
            default -> log.info("Skipping unknown product eventType {} (eventId={})",
                event.eventType(), event.eventId());
        }
    }

    private ProductLifecycleEvent decode(String rawValue) throws Exception {
        JsonNode node = objectMapper.readTree(rawValue);
        if (node.isTextual()) {
            // Legacy double-encoded wire (pre-R1): an old producer
            // JSON-string-encoded the payload — unwrap once before binding.
            node = objectMapper.readTree(node.textValue());
        }
        return objectMapper.treeToValue(node, ProductLifecycleEvent.class);
    }
}
