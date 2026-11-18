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

@Component
@RequiredArgsConstructor
@Slf4j
public class MediaHeadClient {

    @Qualifier("mediaRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

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
