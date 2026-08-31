package com.shop.paymentservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;

import java.util.Map;
import java.util.Set;

public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL_TRANSITIONS = Map.of(
            PaymentStatus.PENDING, Set.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED),
            PaymentStatus.CAPTURED, Set.of(PaymentStatus.REFUNDED));

    private PaymentStateMachine() {
    }

    public static PaymentStatus transition(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null || !LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, from, to);
        }
        return to;
    }
}
