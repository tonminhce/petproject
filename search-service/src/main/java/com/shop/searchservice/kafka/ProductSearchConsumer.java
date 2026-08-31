package com.shop.searchservice.kafka;

import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.searchservice.service.ProductSearchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class ProductSearchConsumer extends BaseKafkaConsumer<String, ProductLifecycleEvent> {

    private final ProductSearchService productSearchService;

    public ProductSearchConsumer(ProductSearchService productSearchService) {
        this.productSearchService = productSearchService;
    }

    @KafkaListener(topics = "shop.product.lifecycle.v1", containerFactory = "searchListenerFactory")
    public void onMessage(ProductLifecycleEvent event, MessageHeaders headers) {
        processMessage(event, headers, this::handleContained);
    }

    // Ack-always poison posture (product-service ProductRatingConsumer
    // precedent): the listener method must never throw — a handler failure is
    // logged and swallowed so the offset still advances. Unknown eventTypes
    // are ack-skipped (spec D1); no DLT (fleet containment rule).
    private void handleContained(ProductLifecycleEvent event) {
        try {
            switch (event.eventType() == null ? "" : event.eventType()) {
                case "ProductCreated", "ProductUpdated" -> productSearchService.index(event);
                case "ProductDeleted" -> productSearchService.delete(event.productId());
                default -> log.info("Skipping unknown product eventType {} (eventId={})",
                    event.eventType(), event.eventId());
            }
        } catch (Exception ex) {
            log.error("Failed to process product event {} for product {}", event.eventId(), event.productId(), ex);
        }
    }
}
