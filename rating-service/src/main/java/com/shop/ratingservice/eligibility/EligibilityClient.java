package com.shop.ratingservice.eligibility;

import com.shop.common.core.viewmodel.ApiResponse;
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

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class EligibilityClient {

    private static final ParameterizedTypeReference<ApiResponse<PurchaseEligibilitySnapshot>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RatingClientProperties props;

    @Qualifier("orderRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    @CircuitBreaker(name = "orderService")
    public boolean isEligible(UUID userId, UUID productId) {
        try {
            ApiResponse<PurchaseEligibilitySnapshot> resp = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/orders/verify-purchase")
                    .queryParam("userId", userId)
                    .queryParam("productId", productId)
                    .build())
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(RESPONSE_TYPE);
            if (resp == null || resp.data() == null) {
                return false;
            }
            return resp.data().eligible();
        } catch (RestClientException ex) {
            log.error("Purchase verification failed for user {} product {} — failing closed", userId, productId, ex);
            return false;
        }
    }

    record PurchaseEligibilitySnapshot(boolean eligible, long deliveredItemCount) {}
}
