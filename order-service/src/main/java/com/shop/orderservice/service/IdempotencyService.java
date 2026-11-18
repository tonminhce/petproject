package com.shop.orderservice.service;

import com.shop.orderservice.dto.response.OrderResponse;

import java.util.Optional;

public interface IdempotencyService {
    Optional<OrderResponse> begin(String key, String actor, String requestHash);

    void complete(String key, String actor, OrderResponse response, int status);

    void abort(String key, String actor, String requestHash);
}
