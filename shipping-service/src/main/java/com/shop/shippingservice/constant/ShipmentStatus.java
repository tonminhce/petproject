package com.shop.shippingservice.constant;

public enum ShipmentStatus {
    CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, CANCELLED;

    public static boolean inFlight(ShipmentStatus s) {
        return s == PICKED_UP || s == IN_TRANSIT || s == OUT_FOR_DELIVERY;
    }
}
