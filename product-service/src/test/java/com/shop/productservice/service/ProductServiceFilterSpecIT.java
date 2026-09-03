package com.shop.productservice.service;

import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave C — finding H21 (filterSpec default status=ACTIVE for storefront).
 *
 * <p>The public storefront catalog must NEVER leak rows whose status is not
 * {@link ProductStatus#ACTIVE}. The {@code ProductController} already
 * defaults a missing {@code status} query param to {@code ACTIVE} (controller
 * layer), but {@code ProductService.findAll} (storefront summary path) was
 * passing a {@code null} status straight through to the JPA
 * {@code Specification}, which then omitted the status predicate entirely —
 * returning DRAFT, DISCONTINUED, and OUT_OF_STOCK rows to any caller that
 * bypassed the controller. This integration test exercises the service-layer
 * default: with a {@code null} status on the filter, the storefront
 * {@code findAll} must still return only {@code ACTIVE} rows.
 *
 * <p>Backoffice and reindex paths are covered separately by
 * {@code findAllDetail} (which intentionally keeps the {@code null = all
 * statuses} semantics so admins and the search-service reindex stream see
 * every row).
 */
class ProductServiceFilterSpecIT extends AbstractIntegrationTest {

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;

    @Test
    void findAll_withNullStatus_defaultsToActive_andHidesDraftRows() {
        // Seed one ACTIVE row (the storefront-visible one) and one DRAFT row
        // (the one the H21 leak was surfacing). Random slugs/skus per run so
        // the seed doesn't collide with rows from sibling IT classes.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID activeId = productRepository.save(Product.builder()
            .title("Active-" + suffix)
            .slug("active-" + suffix)
            .sku("ACT-" + suffix)
            .priceUnit(new BigDecimal("10.00"))
            .quantity(1)
            .status(ProductStatus.ACTIVE)
            .build()).getId();
        UUID draftId = productRepository.save(Product.builder()
            .title("Draft-" + suffix)
            .slug("draft-" + suffix)
            .sku("DRF-" + suffix)
            .priceUnit(new BigDecimal("10.00"))
            .quantity(1)
            .status(ProductStatus.DRAFT)
            .build()).getId();

        // H21: storefront path must default null status → ACTIVE.
        // The service call here bypasses the controller (which already does
        // this), proving the SERVICE layer normalises too — defence in depth
        // so any future caller that forgets the default does not silently
        // leak DRAFT rows into the public catalog.
        var page = productService.findAll(
            new ProductFilter(null, null, null),
            PageRequest.of(0, 50));

        assertThat(page.content())
            .as("storefront findAll with null status must return only ACTIVE rows")
            .extracting(com.shop.productservice.dto.response.ProductSummaryResponse::id,
                com.shop.productservice.dto.response.ProductSummaryResponse::status)
            .contains(Tuple.tuple(activeId, ProductStatus.ACTIVE))
            .doesNotContain(Tuple.tuple(draftId, ProductStatus.DRAFT));
    }

    @Test
    void findAllDetail_withNullStatus_keepsAllStatuses_forBackofficeAndReindex() {
        // Backoffice / reindex path must NOT default to ACTIVE — admins and
        // the search-service reindex stream legitimately need to see DRAFT
        // rows. This pins the deliberate asymmetry: only the storefront
        // summary path gets the safe default.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID activeId = productRepository.save(Product.builder()
            .title("Active2-" + suffix)
            .slug("active2-" + suffix)
            .sku("ACT2-" + suffix)
            .priceUnit(new BigDecimal("10.00"))
            .quantity(1)
            .status(ProductStatus.ACTIVE)
            .build()).getId();
        UUID draftId = productRepository.save(Product.builder()
            .title("Draft2-" + suffix)
            .slug("draft2-" + suffix)
            .sku("DRF2-" + suffix)
            .priceUnit(new BigDecimal("10.00"))
            .quantity(1)
            .status(ProductStatus.DRAFT)
            .build()).getId();

        var page = productService.findAllDetail(
            new ProductFilter(null, null, null),
            PageRequest.of(0, 50));

        assertThat(page.content())
            .as("backoffice findAllDetail with null status must return ALL statuses")
            .extracting(com.shop.productservice.dto.response.ProductDetailResponse::id,
                com.shop.productservice.dto.response.ProductDetailResponse::status)
            .contains(
                Tuple.tuple(activeId, ProductStatus.ACTIVE),
                Tuple.tuple(draftId, ProductStatus.DRAFT));
    }
}
