package com.shop.productservice.job;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.client.MediaHeadClient;
import com.shop.productservice.config.ProductMediaSweepProperties;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductMediaService;
import com.shop.productservice.service.ProductMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H-3 reconciliation sweep unit tests: the cycle is BOUNDED (exactly one page
 * of {@code limit} rows — no pagination loop), fail-safe on media outage (any
 * client failure aborts the remaining cycle — never mass-clearing behind
 * doubt), reuses the consumer's clear path on a 404, keeps live references on
 * a 200, and meters checked/cleared rows.
 */
@ExtendWith(MockitoExtension.class)
class ProductMediaSweepJobTest {

    private static final UUID MEDIA_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID MEDIA_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID MEDIA_C = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Mock ProductRepository productRepository;
    @Mock MediaHeadClient mediaHeadClient;
    @Mock ProductMediaService productMediaService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductMetrics metrics = new ProductMetrics(registry, new NoCacheManager());
    private ProductMediaSweepJob job;
    private ProductMediaSweepProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ProductMediaSweepProperties(true, "0 0 3 * * *", 100);
        job = new ProductMediaSweepJob(productRepository, mediaHeadClient, productMediaService, properties, metrics);
    }

    private Product product(UUID mediaId) {
        return Product.builder()
            .id(UUID.randomUUID())
            .title("p")
            .slug("p-" + mediaId)
            .sku("P-" + mediaId.toString().substring(0, 8))
            .priceUnit(new BigDecimal("1.00"))
            .quantity(1)
            .status(ProductStatus.ACTIVE)
            .mediaId(mediaId)
            .build();
    }

    private void pageReturns(Product... products) {
        when(productRepository.findByMediaIdIsNotNull(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(products)));
    }

    private double checkedCount() {
        return registry.counter("product_media_sweep_checked_total").count();
    }

    private double clearedCount() {
        return registry.counter("product_media_sweep_cleared_total").count();
    }

    @Test
    @DisplayName("disabled → early return, no repository interaction at all")
    void disabled_skipsCycleEntirely() {
        properties = new ProductMediaSweepProperties(false, "0 0 3 * * *", 100);
        job = new ProductMediaSweepJob(productRepository, mediaHeadClient, productMediaService, properties, metrics);

        job.sweep();

        verifyNoInteractions(productRepository, mediaHeadClient, productMediaService);
        assertThat(checkedCount()).isZero();
    }

    @Test
    @DisplayName("bounding/paging: queries exactly ONE page of size=limit; a full page is never followed by a second query")
    void paging_exactlyOnePageOfLimit_noPaginationLoop() {
        // page reports MORE total rows available (hasNext true) — the sweep must
        // still stop after this one page; the next cycle continues.
        List<Product> fullPage = List.of(product(MEDIA_A), product(MEDIA_B));
        var page = new PageImpl<>(fullPage, PageRequest.of(0, properties.limit()), 500);
        when(productRepository.findByMediaIdIsNotNull(any(Pageable.class))).thenReturn(page);
        when(mediaHeadClient.exists(any())).thenReturn(true);

        job.sweep();

        verify(productRepository, times(1)).findByMediaIdIsNotNull(any(Pageable.class));
        verify(productRepository).findByMediaIdIsNotNull(PageRequest.of(0, 100));
        assertThat(checkedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("HEAD 200 → reference kept; 404 → cleared via clearReference with the row's mediaId")
    void headOutcome_decidesKeepOrClear() {
        Product live = product(MEDIA_A);
        Product dead = product(MEDIA_B);
        pageReturns(live, dead);
        when(mediaHeadClient.exists(MEDIA_A)).thenReturn(true);
        when(mediaHeadClient.exists(MEDIA_B)).thenReturn(false);
        when(productMediaService.clearReference(MEDIA_B)).thenReturn(1);

        job.sweep();

        verify(mediaHeadClient).exists(MEDIA_A);
        verify(mediaHeadClient).exists(MEDIA_B);
        verify(productMediaService).clearReference(MEDIA_B);
        verify(productMediaService, never()).clearReference(MEDIA_A);
        assertThat(checkedCount()).isEqualTo(2);
        assertThat(clearedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("404 shared by several page rows → one clearReference call, cleared meter counts ROWS")
    void sharedDeadMedia_clearedMeterCountsRows() {
        Product p1 = product(MEDIA_A);
        Product p2 = product(MEDIA_A);
        pageReturns(p1, p2);
        when(mediaHeadClient.exists(MEDIA_A)).thenReturn(false);
        when(productMediaService.clearReference(MEDIA_A)).thenReturn(2);

        job.sweep();

        verify(productMediaService, times(2)).clearReference(MEDIA_A);
        assertThat(clearedCount()).isEqualTo(4);   // 2 rows × 2 calls (second call is a no-op replay)
    }

    @Test
    @DisplayName("outage (client fail-closed MED-12006) → remaining cycle SKIPPED, no mass clearing")
    void outage_abortsCycle_failSafe() {
        Product first = product(MEDIA_A);
        Product second = product(MEDIA_B);
        Product third = product(MEDIA_C);
        pageReturns(first, second, third);
        when(mediaHeadClient.exists(MEDIA_A))
            .thenThrow(BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE));

        assertThatCode(job::sweep).doesNotThrowAnyException();

        // entire cycle skipped: the failing row's media is never "cleared", the
        // remaining rows are never even checked, no clear path is touched
        verify(mediaHeadClient, times(1)).exists(any());
        verifyNoInteractions(productMediaService);
        assertThat(checkedCount()).isEqualTo(1);
        assertThat(clearedCount()).isZero();
    }

    @Test
    @DisplayName("clear failure (DB blip) mid-cycle also aborts the remaining cycle")
    void clearFailure_abortsRemainingCycle() {
        Product first = product(MEDIA_A);
        Product second = product(MEDIA_B);
        pageReturns(first, second);
        when(mediaHeadClient.exists(MEDIA_A)).thenReturn(false);
        when(productMediaService.clearReference(MEDIA_A))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("db blip"));

        assertThatCode(job::sweep).doesNotThrowAnyException();

        verify(mediaHeadClient, times(1)).exists(any());
        assertThat(clearedCount()).isZero();
    }

    private static final class NoCacheManager implements org.springframework.cache.CacheManager {
        @Override
        public org.springframework.cache.Cache getCache(String name) {
            return null;
        }

        @Override
        public java.util.Collection<String> getCacheNames() {
            return List.of();
        }
    }
}
