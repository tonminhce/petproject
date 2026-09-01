package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Handler for media lifecycle events (media epic spec D4, orphan-kill): a
 * deleted media must never leave dangling product references. Clearing the
 * {@code media_id} of every live product that points at it makes the derived
 * image fall back to the legacy free-text imageUrl automatically (spec D5) —
 * the derived canonical path is computed at mapping time, nothing stored.
 *
 * <p>Each cleared product is re-published as {@code ProductUpdated} through
 * the existing enriched publisher so the search doc refreshes via the
 * established chain (media delete → product update → search refresh).
 * Media-created events carry no product action (audit-only) — ack-skipped by
 * the consumer.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductMediaService {

    private final ProductRepository productRepository;
    private final ProductEventPublisher productEventPublisher;

    @Transactional
    public void clearReference(UUID mediaId) {
        List<Product> affected = productRepository.findByMediaId(mediaId);
        if (affected.isEmpty()) {
            log.info("No products reference media {} — nothing to clear", mediaId);
            return;
        }
        for (Product product : affected) {
            product.setMediaId(null);
            // Spec D4: publish the ProductUpdated snapshot AFTER the save,
            // inside this transaction — REQUIRED propagation joins the outbox
            // insert so the product row and the event row commit atomically.
            Product saved = productRepository.save(product);
            productEventPublisher.publishUpdated(saved);
        }
        log.info("Cleared media reference {} on {} product(s)", mediaId, affected.size());
    }
}
