package com.shop.ratingservice.eligibility;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.keycloak.exception.KeycloakServiceTokenException;
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

    @CircuitBreaker(name = "orderService", fallbackMethod = "isEligibleFallback")
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
                log.error("Purchase verification returned no payload for user {} product {}", userId, productId);
                throw BusinessException.serviceUnavailable("error.service.unavailable",
                        new IllegalStateException("Purchase verification returned no payload"));
            }
            return resp.data().eligible();
        } catch (RestClientException | KeycloakServiceTokenException ex) {
            log.error("Purchase verification is unavailable for user {} product {} - dependency failure", userId, productId, ex);
            throw BusinessException.serviceUnavailable("error.service.unavailable", ex);
        }
    }

    private boolean isEligibleFallback(UUID userId, UUID productId, Throwable throwable) {
        if (throwable instanceof BusinessException businessException) {
            throw businessException;
        }
        log.error("Purchase verification is unavailable for user {} product {} - dependency failure", userId, productId, throwable);
        throw BusinessException.serviceUnavailable("error.service.unavailable", throwable);
    }

    record PurchaseEligibilitySnapshot(boolean eligible, long deliveredItemCount) {}
}
