package com.shop.productservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.Product;
import com.shop.productservice.kafka.RatingLifecycleEvent;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec D4: {@code ProductRatingService.apply} must emit a ProductUpdated
 * outbox row in the SAME transaction as the product row (REQUIRED propagation
 * joins). By the time {@code apply} returns, both rows are committed together
 * — asserted here against the real Postgres outbox table (mirrors the
 * same-tx sanity check in {@code OutboxRelayIntegrationTest}).
 */
class ProductRatingOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired ProductRatingService ratingService;
    @Autowired ProductRepository productRepository;
    @Autowired OutboxEventRepository outboxRepository;

    @Test
    void apply_leavesPendingProductUpdatedOutboxRowCommittedWithTheProductRow() {
        Product product = productRepository.save(Product.builder()
            .title("Rated Thing")
            .slug("rated-thing-" + UUID.randomUUID())
            .sku("RT-" + UUID.randomUUID().toString().substring(0, 8))
            .priceUnit(new BigDecimal("10.00"))
            .quantity(1)
            .status(ProductStatus.ACTIVE)
            .build());

        ratingService.apply(new RatingLifecycleEvent(
            "44444444-4444-4444-4444-444444444444",
            "rating.submitted.v1",
            "2026-08-31T10:00:00Z",
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            product.getId(),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            5,
            "Great product, highly recommend",
            true,
            "CREATED",
            true,
            new BigDecimal("4.50"),
            2));

        // apply()'s transaction has committed: the product row and its
        // ProductUpdated outbox row (still PENDING — relay not yet ticked).
        List<OutboxEvent> pending = outboxRepository
            .findByStatusOrderByIdAsc(OutboxStatus.PENDING, Pageable.unpaged());
        OutboxEvent updated = pending.stream()
            .filter(e -> "ProductUpdated".equals(e.getEventType()))
            .filter(e -> product.getId().equals(e.getAggregateId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No PENDING ProductUpdated outbox row committed for product " + product.getId()));

        assertThat(updated.getAggregateType()).isEqualTo("Product");
        assertThat(updated.getTopic()).isEqualTo("shop.product.lifecycle.v1");
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }
}
