package com.shop.productservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
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

    // groupId is pinned: it is the consumer's identity, and changing it resets
    // the committed offsets and replays the topic (see BaseKafkaListenerConfig)
    @KafkaListener(topics = KafkaTopicContract.SHOP_RATING_LIFECYCLE, groupId = "product-service",
            containerFactory = "ratingListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {

        processMessage(rawValue, headers, RatingLifecycleEvent.class, productRatingService::apply);
    }
}
