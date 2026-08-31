package com.shop.ratingservice.eligibility;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.ratingservice.config.RatingClientProperties;
import com.shop.ratingservice.security.ServiceTokenProvider;
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
 * Order-service client for the verified-purchase eligibility gate (rating
 * design D1). Per-call Authorization header from {@link ServiceTokenProvider} —
 * the verify-purchase endpoint is SERVICE-role.
 *
 * <p>Error posture — fail-closed: ANY failure (non-2xx, timeout, malformed
 * body) maps to {@code false} instead of a thrown exception; the submit guard
 * turns that into RTG-11001. The raw downstream exception never leaves this
 * class (logged instead). There is no benign "proceed" answer for a purchase
 * we could not verify, so {@code false} plus the caller-side guard carries the
 * fail-closed semantics with one single mapped code at the seam.</p>
 */
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

    /**
     * @return true only when order-service reports at least one DELIVERED order
     *         item for ({@code userId}, {@code productId}); false on any
     *         failure, empty page, or unparseable body (fail-closed).
     */
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
