package com.shop.ratingservice.eligibility;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.ratingservice.config.RatingClientProperties;
import com.shop.ratingservice.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
public class EligibilityClient {

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<OrderItemSnapshot>>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RatingClientProperties props;

    @Qualifier("orderRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    @CircuitBreaker(name = "orderService")
    public boolean isEligible(UUID userId, UUID productId) {
        try {
            ApiResponse<PageResponse<OrderItemSnapshot>> resp = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/orders/verify-purchase")
                    .queryParam("userId", userId)
                    .queryParam("productId", productId)
                    .build())
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(RESPONSE_TYPE);
            if (resp == null || resp.data() == null || resp.data().content() == null) {
                return false;
            }
            return !resp.data().content().isEmpty();
        } catch (RestClientException ex) {
            log.error("Purchase verification failed for user {} product {} — failing closed", userId, productId, ex);
            return false;
        }
    }

    record OrderItemSnapshot(UUID productId, String productTitle, Integer quantity,
                             BigDecimal unitPrice, BigDecimal lineTotal) {}
}
