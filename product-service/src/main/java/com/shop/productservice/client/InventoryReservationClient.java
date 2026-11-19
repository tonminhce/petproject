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

/**
 * Inventory-service client for the delete-time stock guard (F-060).
 *
 * <p>A product must not leave the catalogue while its stock is reserved or
 * committed - the checkout could still commit a reservation for a product that
 * no longer exists, and the reserved stock would never return to the sellable
 * pool. The reservations live in the inventory database, so the catalogue
 * cannot join them and has to ask.</p>
 *
 * <p>Error posture — fail-closed, the media purge precedent
 * ({@code MediaReferenceClient}): ANY HTTP failure (non-2xx, timeout,
 * connection refused, malformed body) maps to an EMPTY result instead of a
 * thrown exception — the downstream {@link RestClientException} never leaves
 * this class (it is logged instead). An empty result means "cannot prove the
 * product is free of live reservations"; the caller turns that into a refusal
 * to delete. A delete must never happen on doubt.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryReservationClient {

    private static final ParameterizedTypeReference<ApiResponse<ActiveReservationCount>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Qualifier("inventoryRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    /**
     * @return the number of live (PENDING + COMMITTED) reservations when
     *         inventory-service answers 200 with a parseable body;
     *         {@link OptionalLong#empty()} on ANY failure (fail-closed — the
     *         caller must treat unknown as "still reserved")
     */
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

    /** Wire payload of inventory's internal reservation-count endpoint. */
    record ActiveReservationCount(UUID productId, long activeReservationCount) {
    }
}
