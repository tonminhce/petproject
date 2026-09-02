package com.shop.orderservice.service;

import com.shop.orderservice.dto.response.OrderResponse;

import java.util.Optional;

public interface IdempotencyService {
    /**
     * {@code actor} is the row's owner label (H-6): the customer sub for
     * checkout, the ADMIN sub or {@code service:<azp>} for confirm — stored
     * verbatim so machine callers are never misattributed to a UUID.
     *
     * @return cached response if key already complete; empty Optional if owner; throws on conflict.
     */
    Optional<OrderResponse> begin(String key, String actor, String requestHash);

    /** Update in-flight row with final response (same TX as saga). */
    void complete(String key, String actor, OrderResponse response, int status);

    /**
     * Best-effort delete in-flight row on saga failure (REQUIRES_NEW).
     * {@code requestHash} guards against ever deleting a row this execution
     * does not own — only an in-flight row with the identical hash is removed.
     */
    void abort(String key, String actor, String requestHash);
}
