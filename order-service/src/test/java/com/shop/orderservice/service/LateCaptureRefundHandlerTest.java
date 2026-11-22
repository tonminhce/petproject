package com.shop.orderservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.client.RefundOutcome;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LateCaptureRefundHandlerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock OrderRepository orderRepository;
    @Mock PaymentServiceClient paymentServiceClient;

    private LateCaptureRefundHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LateCaptureRefundHandler(orderRepository, paymentServiceClient);
    }

    private Order order(OrderStatus status) {
        return Order.builder().id(ORDER_ID).status(status).build();
    }

    @Test
    @DisplayName("ORD-1: a late capture of a cancelled order triggers exactly one refund through the platform refund path")
    void lateCaptureOfACancelledOrder_refundsExactlyOnceThroughThePlatformRefundPath() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));
        when(paymentServiceClient.refundByOrderId(eq(ORDER_ID), isNull(), isNull(), any(UUID.class)))
                .thenReturn(RefundOutcome.REFUNDED);

        handler.refundCapturedCancelledOrder(ORDER_ID, PAYMENT_ID);

        verify(paymentServiceClient, times(1)).refundByOrderId(eq(ORDER_ID), isNull(), isNull(),
                eq(LateCaptureRefundHandler.refundReference(ORDER_ID, PAYMENT_ID)));
        verifyNoMoreInteractions(paymentServiceClient);
    }

    @Test
    @DisplayName("ORD-1: a replay of the same captured event carries the identical reference and settles without a new refund")
    void aReplayOfTheSameLateCapture_settlesThroughTheLedgerWithoutANewRefund() {
        UUID reference = LateCaptureRefundHandler.refundReference(ORDER_ID, PAYMENT_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));
        when(paymentServiceClient.refundByOrderId(ORDER_ID, null, null, reference))
                .thenReturn(RefundOutcome.REFUNDED)
                .thenReturn(RefundOutcome.ALREADY_REFUNDED);

        assertThatCode(() -> {
            handler.refundCapturedCancelledOrder(ORDER_ID, PAYMENT_ID);
            handler.refundCapturedCancelledOrder(ORDER_ID, PAYMENT_ID);
        }).doesNotThrowAnyException();

        verify(paymentServiceClient, times(2)).refundByOrderId(ORDER_ID, null, null, reference);
        verifyNoMoreInteractions(paymentServiceClient);
    }

    @Test
    @DisplayName("The refund reference is derived from the payment and the order and is stable across replays")
    void theRefundReference_isDerivedFromThePaymentAndTheOrderAndIsStable() {
        UUID otherOrderId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID otherPaymentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID first = LateCaptureRefundHandler.refundReference(ORDER_ID, PAYMENT_ID);
        UUID replay = LateCaptureRefundHandler.refundReference(ORDER_ID, PAYMENT_ID);
        UUID otherPayment = LateCaptureRefundHandler.refundReference(ORDER_ID, otherPaymentId);
        UUID otherOrder = LateCaptureRefundHandler.refundReference(otherOrderId, PAYMENT_ID);

        assertThat(first).isEqualTo(replay);
        assertThat(first).isNotEqualTo(otherPayment);
        assertThat(first).isNotEqualTo(otherOrder);
    }

    @Test
    @DisplayName("ORD-1: a late capture of a non-cancelled order behaves exactly as before - no refund is triggered")
    void aLateCaptureForAnOrderThatIsNotCancelled_isIgnoredWithoutARefund() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED)));

        assertThatCode(() -> handler.refundCapturedCancelledOrder(ORDER_ID, PAYMENT_ID))
                .doesNotThrowAnyException();

        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
        verify(paymentServiceClient, never()).refundByOrderId(any());
    }

    @Test
    @DisplayName("An unknown order is ack-skipped without a refund attempt")
    void anUnknownOrder_isAckSkippedWithoutARefund() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> handler.refundCapturedCancelledOrder(ORDER_ID, PAYMENT_ID))
                .doesNotThrowAnyException();

        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A failed refund propagates so the captured event is retried and the ledger settles it exactly once")
    void aFailedRefund_isRethrownSoTheCapturedEventIsRetried() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));
        when(paymentServiceClient.refundByOrderId(eq(ORDER_ID), isNull(), isNull(), any(UUID.class)))
                .thenThrow(BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment"));

        assertThatThrownBy(() -> handler.refundCapturedCancelledOrder(ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class);
    }
}
