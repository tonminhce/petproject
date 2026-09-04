package com.shop.mediaservice.client;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.mediaservice.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Product-service client for the purge-side reference gate (H-4): a lightweight
 * {@code GET /internal/products/media-references/{id}} returning how many live
 * products still point at the media. Per-call Authorization header from
 * {@link ServiceTokenProvider} — the endpoint is SERVICE-role.
 *
 * <p>Error posture — fail-closed, EligibilityClient shape: ANY HTTP failure
 * (non-2xx, timeout, connection refused, malformed body) maps to an EMPTY
 * result instead of a thrown exception — the downstream {@link RestClientException}
 * never leaves this class (logged instead). What CAN propagate is the service
 * token itself: {@link ServiceTokenProvider#getToken()} throws IllegalStateException
 * when Keycloak rejects the client_credentials grant (e.g. empty/rotated
 * secret). That is still fail-safe end to end — the caller
 * ({@code ProductMediaReferenceChecker}) maps an empty result to REFERENCED and
 * the purge job additionally treats any checker exception as REFERENCED — but
 * it is not swallowed here. An empty result means "cannot prove the media is
 * unreferenced" and the caller turns that into fail-safe REFERENCED — a purge
 * must never hard-delete on doubt. There is no benign "purge anyway" answer
 * when product is unreachable.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaReferenceClient {

    private static final ParameterizedTypeReference<ApiResponse<MediaReferenceCount>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Qualifier("productRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    /**
     * @return the live product reference count when product-service answers
     *         200 with a parseable body; {@link OptionalLong#empty()} on ANY
     *         failure (fail-closed — the caller must treat unknown as
     *         referenced)
     */
    @CircuitBreaker(name = "productService")
    public OptionalLong referenceCount(UUID mediaId) {
        try {
            ApiResponse<MediaReferenceCount> resp = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/{mediaId}")
                    .build(mediaId))
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(RESPONSE_TYPE);
            if (resp == null || resp.data() == null) {
                log.error("Reference-count response unusable for media {} — failing closed", mediaId);
                return OptionalLong.empty();
            }
            return OptionalLong.of(resp.data().referenceCount());
        } catch (RestClientException ex) {
            log.error("Reference-count check failed for media {} — failing closed (treat as referenced)",
                mediaId, ex);
            return OptionalLong.empty();
        }
    }

    /** Wire payload of product's internal reference-count endpoint. */
    record MediaReferenceCount(UUID mediaId, long referenceCount) {}
}
