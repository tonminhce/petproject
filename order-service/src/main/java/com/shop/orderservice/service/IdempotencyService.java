package com.shop.orderservice.service;

import com.shop.orderservice.dto.response.OrderResponse;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyService {
    /** @return cached response if key already complete; empty Optional if owner; throws on conflict. */
    Optional<OrderResponse> begin(String key, UUID userId, String requestHash);

    /** Update in-flight row with final response (same TX as saga). */
    void complete(String key, UUID userId, OrderResponse response, int status);

    /**
     * Best-effort delete in-flight row on saga failure (REQUIRES_NEW).
     * {@code requestHash} guards against ever deleting a row this execution
     * does not own — only an in-flight row with the identical hash is removed.
     */
    void abort(String key, UUID userId, String requestHash);
}
