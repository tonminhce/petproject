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

@Component
@RequiredArgsConstructor
@Slf4j
public class MediaReferenceClient {

    private static final ParameterizedTypeReference<ApiResponse<MediaReferenceCount>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Qualifier("productRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

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

    record MediaReferenceCount(UUID mediaId, long referenceCount) {}
}
