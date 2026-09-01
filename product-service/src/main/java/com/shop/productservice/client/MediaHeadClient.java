package com.shop.productservice.client;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Media-service client for the Option C write-time existence check (media
 * epic spec D5): a lightweight {@code HEAD /api/v1/medias/{id}} (existence
 * only — NO presign) so admin typos on create/update never ship broken
 * images. Per-call Authorization header from {@link ServiceTokenProvider}.
 *
 * <p>Error posture — three-valued, per the binding design: 200 → {@code true};
 * 404 → {@code false} (caller rejects with MED-12004); ANY other failure
 * (timeout, connection refused, 5xx, unexpected 4xx) → throws MED-12006
 * (503). An unavailable dependency must FAIL THE WRITE — a product must never
 * be persisted with an unverified media reference. This mirrors the fleet's
 * fail-fast cross-service write gates (search's reindex source client maps
 * downstream failures to its own 503 domain code).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaHeadClient {

    @Qualifier("mediaRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    /**
     * @return {@code true} when media-service reports the media exists (HEAD 2xx),
     *         {@code false} when it reports not found (HEAD 404)
     * @throws BusinessException MED-12006 (503) when media is unreachable or
     *                          answers unexpectedly — the write must not proceed
     */
    public boolean exists(UUID mediaId) {
        try {
            ResponseEntity<Void> resp = restClient.head()
                .uri(uriBuilder -> uriBuilder.path(ApiPaths.MEDIAS + "/{id}").build(mediaId))
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .toBodilessEntity();
            return resp.getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException.NotFound notFound) {
            log.info("Media existence check: media {} not found", mediaId);
            return false;
        } catch (RestClientException ex) {
            log.error("Media existence check failed for media {} — failing the write", mediaId, ex);
            throw BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        }
    }
}
