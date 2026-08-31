package com.shop.paymentservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentStateMachineTest {

    @Test
    void pendingToCapturedIsLegal() {
        assertEquals(PaymentStatus.CAPTURED,
                PaymentStateMachine.transition(PaymentStatus.PENDING, PaymentStatus.CAPTURED));
    }

    @Test
    void pendingToFailedIsLegal() {
        assertEquals(PaymentStatus.FAILED,
                PaymentStateMachine.transition(PaymentStatus.PENDING, PaymentStatus.FAILED));
    }

    @Test
    void capturedToRefundedIsLegal() {
        assertEquals(PaymentStatus.REFUNDED,
                PaymentStateMachine.transition(PaymentStatus.CAPTURED, PaymentStatus.REFUNDED));
    }

    @Test
    void capturedToCapturedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.CAPTURED, PaymentStatus.CAPTURED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void failedToCapturedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.FAILED, PaymentStatus.CAPTURED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void failedToRefundedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.FAILED, PaymentStatus.REFUNDED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void pendingToRefundedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.PENDING, PaymentStatus.REFUNDED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void refundedToPendingIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.REFUNDED, PaymentStatus.PENDING));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void refundedToCapturedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.REFUNDED, PaymentStatus.CAPTURED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void refundedToFailedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.REFUNDED, PaymentStatus.FAILED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void refundedToRefundedIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.REFUNDED, PaymentStatus.REFUNDED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void nullFromIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(null, PaymentStatus.CAPTURED));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }

    @Test
    void nullToIsIllegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PaymentStateMachine.transition(PaymentStatus.PENDING, null));

        assertEquals(ErrorCode.PAYMENT_INVALID_STATE.getCode(), ex.getErrorCode());
    }
}
