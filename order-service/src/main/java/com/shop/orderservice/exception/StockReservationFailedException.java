package com.shop.orderservice.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class StockReservationFailedException extends RuntimeException {
    private final UUID productId;

    public StockReservationFailedException(UUID productId, Throwable cause) {
        super("Failed to reserve stock for product " + productId, cause);
        this.productId = productId;
    }
}
