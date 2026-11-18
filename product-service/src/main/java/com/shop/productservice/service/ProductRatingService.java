package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import com.shop.productservice.kafka.RatingLifecycleEvent;
import com.shop.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRatingService {

    private final ProductRepository productRepository;
    private final ProductEventPublisher productEventPublisher;

    @Transactional
    public void apply(RatingLifecycleEvent event) {
        var found = productRepository.findById(event.productId());
        if (found.isEmpty()) {
            log.info("Product {} not found for rating event {}, ack-skipping", event.productId(), event.eventId());
            return;
        }
        Product product = found.get();
        product.setAvgRating(event.avgRating());
        product.setRatingCount(event.ratingCount());
        Product saved = productRepository.save(product);
        productEventPublisher.publishUpdated(saved);
    }
}
