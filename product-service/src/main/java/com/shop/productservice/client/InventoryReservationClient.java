package com.shop.productservice.client;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.productservice.security.ServiceTokenProvider;
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
public class InventoryReservationClient {

    private static final ParameterizedTypeReference<ApiResponse<ActiveReservationCount>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Qualifier("inventoryRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    public OptionalLong activeReservationCount(UUID productId) {
        try {
            ApiResponse<ActiveReservationCount> resp = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(ApiPaths.INTERNAL_INVENTORY_RESERVATIONS + "/{productId}")
                            .build(productId))
                    .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (resp == null || resp.data() == null) {
                log.error("Reservation-count response unusable for product {} — failing closed", productId);
                return OptionalLong.empty();
            }
            return OptionalLong.of(resp.data().activeReservationCount());
        } catch (RestClientException ex) {
            log.error("Reservation-count check failed for product {} — failing closed "
                    + "(the product is NOT deleted)", productId, ex);
            return OptionalLong.empty();
        }
    }

    record ActiveReservationCount(UUID productId, long activeReservationCount) {
    }
}
