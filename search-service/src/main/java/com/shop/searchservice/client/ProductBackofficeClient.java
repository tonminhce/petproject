package com.shop.searchservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.config.ShopServicesProperties;
import com.shop.searchservice.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Product-service client for the reindex source stream (spec D5): pages
 * through the backoffice full-snapshot list with the SERVICE token attached
 * per-call (verify-purchase pattern).
 *
 * <p>Error posture — retry-free fail-fast: ANY failure (non-2xx, timeout,
 * malformed/empty body) aborts immediately as SRH-12002 (503). A partial
 * reindex source is never a benign answer — the caller (ReindexService) aborts
 * WITHOUT touching the alias. There is no retry loop: the endpoint is
 * idempotent and the operation is re-runnable.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductBackofficeClient {

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductSnapshot>>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final ShopServicesProperties props;

    @Qualifier("productRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    /**
     * Fetches one ACTIVE-products page from the backoffice list.
     *
     * @throws BusinessException SRH-12002 (503) on any failure — never a raw
     *                         downstream exception
     */
    public PageResponse<ProductSnapshot> fetchPage(int page, int size) {
        try {
            ApiResponse<PageResponse<ProductSnapshot>> resp = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(ApiPaths.BACKOFFICE_PRODUCTS)
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .queryParam("status", "ACTIVE")
                    .build())
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(RESPONSE_TYPE);
            if (resp == null || resp.data() == null) {
                log.error("Reindex source page {} returned no data — failing fast", page);
                throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
            }
            return resp.data();
        } catch (RestClientException ex) {
            log.error("Reindex source page {} fetch failed — failing fast", page, ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }

    /**
     * The reindex source row — the {@code ProductDetailResponse} wire shape
     * (priceUnit/categoryTitle field names verbatim, §4(5) unknown-field
     * tolerance). {@code ProductDocuments} adapts it to the D3 doc shape.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductSnapshot(
        UUID id,
        String title,
        String slug,
        String description,
        BigDecimal priceUnit,
        String status,
        String imageUrl,
        BigDecimal avgRating,
        Integer ratingCount,
        UUID categoryId,
        String categoryTitle,
        UUID brandId,
        String brandName,
        String updatedAt
    ) {}
}
