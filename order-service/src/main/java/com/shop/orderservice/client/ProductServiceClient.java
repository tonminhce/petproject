package com.shop.orderservice.client;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * ⚠️ P0-5 — Unwraps {@code ApiResponse<ProductSnapshot>} envelope. Product-service
 * returns ALL endpoints wrapped in {@code ApiResponse<T>} — calling
 * {@code .body(ProductSnapshot.class)} would deserialize {@code {success, code, message, data, ...}}
 * directly into {@code ProductSnapshot} → all fields null → NPE on {@code .unitPrice()}.
 *
 * <p>Use {@link ParameterizedTypeReference} to capture the generic type, then
 * {@code .data()} to extract the payload.</p>
 */
@Component
@RequiredArgsConstructor
public class ProductServiceClient {

    @Qualifier("productRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<ProductSnapshot>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Cacheable(value = "productPrice", key = "#productId")
    public ProductSnapshot getProduct(UUID productId) {
        ApiResponse<ProductSnapshot> resp = restClient.get()
            .uri("/api/v1/products/{id}", productId)
            .retrieve()
            .body(RESPONSE_TYPE);
        return resp.data();
    }
}
