package com.shop.orderservice.client;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.exception.ProductLookupRejectedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProductServiceClient {

    @Qualifier("productRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<ProductSnapshot>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @CircuitBreaker(name = "productService")
    @Cacheable(value = "productPrice", key = "#productId.toString() + ':v2'")
    public ProductSnapshot getProduct(UUID productId) {
        ApiResponse<ProductSnapshot> resp;
        try {
            resp = restClient.get()
                .uri("/api/v1/products/{id}", productId)
                .retrieve()
                .body(RESPONSE_TYPE);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ProductLookupRejectedException(productId);
        }
        return resp.data();
    }
}
