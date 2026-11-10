package com.shop.orderservice.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.consumer.PoisonRecordException;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentLifecycleConsumerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock OrderService orderService;

    private PaymentLifecycleConsumer consumer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new PaymentLifecycleConsumer(orderService);
    }

    private PaymentLifecycleEvent paymentCapturedEvent() {
        return new PaymentLifecycleEvent(
                "evt-123",
                "payment.captured.v1",
                "2026-09-07T10:00:00Z",
                ORDER_ID,
                PAYMENT_ID,
                BigDecimal.valueOf(100),
                "USD",
                "CAPTURED",
                "PENDING"
        );
    }

    @Test
    @DisplayName("Payment captured event invokes orderService.confirmOrder with system actor and idempotent key")
    void onMessage_paymentCaptured_invokesConfirmOrder() throws JsonProcessingException {
        PaymentLifecycleEvent event = paymentCapturedEvent();

        consumer.onMessage(objectMapper.writeValueAsString(event), new MessageHeaders(new HashMap<>()));

        verify(orderService).confirmOrder(ORDER_ID, "system:payment-consumer", "payment-confirm-" + PAYMENT_ID);
    }

    @Test
    @DisplayName("Non-captured event (e.g. payment.failed.v1) does not invoke confirmOrder")
    void onMessage_nonCapturedEvent_doesNotConfirmOrder() throws JsonProcessingException {
        PaymentLifecycleEvent event = new PaymentLifecycleEvent(
                "evt-123",
                "payment.failed.v1",
                "2026-09-07T10:00:00Z",
                ORDER_ID,
                PAYMENT_ID,
                BigDecimal.valueOf(100),
                "USD",
                "FAILED",
                "PENDING"
        );

        consumer.onMessage(objectMapper.writeValueAsString(event), new MessageHeaders(new HashMap<>()));

        verify(orderService, never()).confirmOrder(any(), any(), any());
    }

    @Test
    @DisplayName("F-001: a poison payload is dead-lettered - the handler never runs")
    void onMessage_poisonPayload_isRoutedToTheDeadLetterTopic() {
        assertThatThrownBy(() -> consumer.onMessage("{ not valid json }", new MessageHeaders(new HashMap<>())))
                .isInstanceOf(PoisonRecordException.class);

        verify(orderService, never()).confirmOrder(any(), any(), any());
    }

    @Test
    @DisplayName("Payment captured event on already-transitioned order is swallowed gracefully")
    void onMessage_paymentCaptured_orderAlreadyTransitioned_doesNotThrow() throws JsonProcessingException {
        PaymentLifecycleEvent event = paymentCapturedEvent();
        org.mockito.Mockito.doThrow(com.shop.common.core.exception.BusinessException.of(
                com.shop.common.core.exception.ErrorCode.ORDER_INVALID_STATE_TRANSITION,
                com.shop.orderservice.constant.OrderStatus.CONFIRMED,
                com.shop.orderservice.constant.OrderStatus.CONFIRMED))
                .when(orderService).confirmOrder(any(), any(), any());

        assertThatCode(() -> consumer.onMessage(objectMapper.writeValueAsString(event), new MessageHeaders(new HashMap<>())))
                .doesNotThrowAnyException();
    }
}
