package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductMediaService {

    private final ProductRepository productRepository;
    private final ProductEventPublisher productEventPublisher;

    @Transactional
    public int clearReference(UUID mediaId) {
        List<Product> affected = productRepository.findByMediaId(mediaId);
        if (affected.isEmpty()) {
            log.info("No products reference media {} — nothing to clear", mediaId);
            return 0;
        }
        for (Product product : affected) {
            product.setMediaId(null);
            Product saved = productRepository.save(product);
            productEventPublisher.publishUpdated(saved);
        }
        log.info("Cleared media reference {} on {} product(s)", mediaId, affected.size());
        return affected.size();
    }

    public long referenceCount(UUID mediaId) {
        long count = productRepository.countByMediaId(mediaId);
        log.debug("Reference count for media {}: {} product(s)", mediaId, count);
        return count;
    }
}
