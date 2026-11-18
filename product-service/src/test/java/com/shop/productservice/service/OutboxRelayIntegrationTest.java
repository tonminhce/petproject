package com.shop.productservice.service;

import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.service.BrandService;
import com.shop.productservice.service.CategoryService;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboxRelayIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("product.outbox.poll-interval-ms", () -> "3600000");
    }

    @Autowired ProductService productService;
    @Autowired CategoryService categoryService;
    @Autowired BrandService brandService;
    @Autowired OutboxEventRepository outboxRepo;

    @Test
    void relay_publishesProductCreatedEventToKafka() {
        long sentBefore = outboxRepo.countByStatus(OutboxStatus.SENT);

        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", null,
            "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null);
        productService.create(req);

        long pendingAfterCreate = outboxRepo.countByStatus(OutboxStatus.PENDING);
        assertThat(pendingAfterCreate).isGreaterThanOrEqualTo(1L);

        outboxRelay().relay();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long pendingCount = outboxRepo.countByStatus(OutboxStatus.PENDING);
            long sentCount = outboxRepo.countByStatus(OutboxStatus.SENT);
            assertThat(pendingCount).isEqualTo(0L);
            assertThat(sentCount).isGreaterThan(sentBefore);
        });
    }

    @Test
    void relay_publishesCategoryAndBrandEventsToKafka() {
        long sentBefore = outboxRepo.countByStatus(OutboxStatus.SENT);

        categoryService.create(new CategoryCreateRequest("Electronics", "electronics", null, null));
        brandService.create(new BrandCreateRequest("Acme", "acme", null, null));

        outboxRelay().relay();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(outboxRepo.countByStatus(OutboxStatus.PENDING)).isEqualTo(0L);
            assertThat(outboxRepo.countByStatus(OutboxStatus.SENT)).isGreaterThanOrEqualTo(sentBefore + 2);
        });
    }

    private OutboxRelay outboxRelay() {
        return applicationContext.getBean(OutboxRelay.class);
    }
}
