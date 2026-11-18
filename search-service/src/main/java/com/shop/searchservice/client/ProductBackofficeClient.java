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
