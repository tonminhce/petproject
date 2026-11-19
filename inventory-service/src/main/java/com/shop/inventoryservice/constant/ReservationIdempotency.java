package com.shop.inventoryservice.constant;

public final class ReservationIdempotency {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final int MAX_KEY_LENGTH = 64;

    private ReservationIdempotency() {
    }
}
