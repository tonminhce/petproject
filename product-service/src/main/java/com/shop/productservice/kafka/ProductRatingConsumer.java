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
    public void onMessage(String rawValue, MessageHeaders headers) {
        // H-1 raw-wire entry: the base unwraps-once + binds the typed event;
        // a decode failure is a contained ack-skip inside the base.
        processMessage(rawValue, headers, RatingLifecycleEvent.class, productRatingService::apply);
    }
}
