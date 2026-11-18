package com.shop.searchservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.searchservice.service.ProductSearchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class ProductSearchConsumer extends BaseKafkaConsumer<String, String> {

    private final ProductSearchService productSearchService;
    private final ObjectMapper objectMapper;

    public ProductSearchConsumer(ProductSearchService productSearchService, ObjectMapper objectMapper) {
        this.productSearchService = productSearchService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicContract.SHOP_PRODUCT_LIFECYCLE, groupId = "search-service",
            containerFactory = "searchListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {
        processMessage(rawValue, headers, this::handleContained);
    }

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
            node = objectMapper.readTree(node.textValue());
        }
        return objectMapper.treeToValue(node, ProductLifecycleEvent.class);
    }
}
