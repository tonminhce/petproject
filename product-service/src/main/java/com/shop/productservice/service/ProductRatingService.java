package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import com.shop.productservice.kafka.RatingLifecycleEvent;
import com.shop.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handler for rating lifecycle events (spec D5, decision Q2-A): dumb
 * idempotent copy of the carried avgRating/ratingCount snapshot onto the
 * product row. No recompute, no rating-service client, no action-based
 * filtering — replaying the same event overwrites with identical values.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRatingService {

    private final ProductRepository productRepository;

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
        productRepository.save(product);
    }
}
