package com.shop.orderservice.exception;

import lombok.Getter;

import java.util.UUID;

/**
 * Internal control-flow signal: a downstream inventory reserve could not complete
 * (409 insufficient stock / 404 no inventory row). Deliberately NOT a
 * {@code BusinessException} — it never escapes to the API layer;
 * {@code OrderServiceImpl} catches it, runs the compensation releases, and
 * rethrows as {@code BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED)}.
 * It exists to carry the {@code productId} through the catch-and-compensate path.
 */
@Getter
public class StockReservationFailedException extends RuntimeException {
    private final UUID productId;

    public StockReservationFailedException(UUID productId, Throwable cause) {
        super("Failed to reserve stock for product " + productId, cause);
        this.productId = productId;
    }
}
