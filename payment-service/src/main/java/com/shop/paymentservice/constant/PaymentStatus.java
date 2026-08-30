package com.shop.paymentservice.constant;

import java.util.Set;

public enum PaymentStatus {

    PENDING, CAPTURED, FAILED, REFUNDED;

    public static final Set<PaymentStatus> TERMINAL_WEBHOOK_STATES = Set.of(CAPTURED, FAILED, REFUNDED);
}
