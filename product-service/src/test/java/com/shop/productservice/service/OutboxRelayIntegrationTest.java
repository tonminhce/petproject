package com.shop.productservice.service;

import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.entity.ProductStatus;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for the full outbox → Kafka path:
 *
 * <ol>
 *   <li>{@link ProductService#create} writes one {@code OutboxEvent} row in
 *       the same DB transaction as the product.</li>
 *   <li>{@link OutboxRelay#relay} is invoked manually (avoid waiting for the
 *       {@code @Scheduled} timer) and publishes to the Testcontainers Kafka.</li>
 *   <li>The outbox row flips from {@link OutboxStatus#PENDING} to
 *       {@link OutboxStatus#SENT}.</li>
 * </ol>
 */
class OutboxRelayIntegrationTest extends AbstractIntegrationTest {

    @Autowired ProductService productService;
    @Autowired OutboxEventRepository outboxRepo;

    @Test
    void relay_publishesProductCreatedEventToKafka() {
        long sentBefore = outboxRepo.countByStatus(OutboxStatus.SENT);

        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", null,
            "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null);
        productService.create(req);

        // Sanity: the publisher wrote a PENDING outbox row in the same TX as the product.
        long pendingAfterCreate = outboxRepo.countByStatus(OutboxStatus.PENDING);
        assertThat(pendingAfterCreate).isGreaterThanOrEqualTo(1L);

        // Drain — invoke the relay directly instead of waiting for the @Scheduled timer.
        outboxRelay().relay();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long pendingCount = outboxRepo.countByStatus(OutboxStatus.PENDING);
            long sentCount = outboxRepo.countByStatus(OutboxStatus.SENT);
            assertThat(pendingCount).isEqualTo(0L);
            assertThat(sentCount).isGreaterThan(sentBefore);
        });
    }

    private OutboxRelay outboxRelay() {
        return applicationContext.getBean(OutboxRelay.class);
    }
}
