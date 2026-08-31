package com.shop.productservice.kafka;

import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.productservice.service.ProductRatingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class ProductRatingConsumer extends BaseKafkaConsumer<String, RatingLifecycleEvent> {

    private final ProductRatingService productRatingService;

    public ProductRatingConsumer(ProductRatingService productRatingService) {
        this.productRatingService = productRatingService;
    }

    @KafkaListener(topics = "shop.rating.lifecycle.v1", containerFactory = "ratingListenerFactory")
    public void onMessage(RatingLifecycleEvent event, MessageHeaders headers) {
        processMessage(event, headers, this::handleContained);
    }

    // Ack-always poison posture (order-service ShippingDeliveredConsumer
    // precedent): the listener method must never throw — a handler failure is
    // logged and swallowed so the offset still advances.
    private void handleContained(RatingLifecycleEvent event) {
        try {
            productRatingService.apply(event);
        } catch (Exception ex) {
            log.error("Failed to process rating event {} for product {}", event.eventId(), event.productId(), ex);
        }
    }
}
