package com.shop.orderservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.orderservice.service.LateCaptureRefundHandler;
import com.shop.orderservice.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class PaymentLifecycleConsumer extends BaseKafkaConsumer<String, PaymentLifecycleEvent> {

    static final String EVENT_TYPE_CAPTURED = "payment.captured.v1";

    private final OrderService orderService;
    private final LateCaptureRefundHandler lateCaptureRefundHandler;

    public PaymentLifecycleConsumer(OrderService orderService, LateCaptureRefundHandler lateCaptureRefundHandler) {
        this.orderService = orderService;
        this.lateCaptureRefundHandler = lateCaptureRefundHandler;
    }

    @KafkaListener(
            topics = KafkaTopicContract.SHOP_PAYMENT_LIFECYCLE,
            containerFactory = "paymentListenerFactory",
            groupId = "order-service-payment-group"
    )
    public void onMessage(String rawValue, MessageHeaders headers) {
        processMessage(rawValue, headers, PaymentLifecycleEvent.class, this::handle);
    }

    private void handle(PaymentLifecycleEvent event) {
        if (event != null && EVENT_TYPE_CAPTURED.equals(event.eventType())) {
            log.info("Processing {} for orderId={}, paymentId={}", event.eventType(), event.orderId(), event.paymentId());
            try {
                orderService.confirmOrder(event.orderId(), "system:payment-consumer", "payment-confirm-" + event.paymentId());
            } catch (BusinessException ex) {
                if (ErrorCode.ORDER_INVALID_STATE_TRANSITION.getCode().equals(ex.getErrorCode())) {
                    lateCaptureRefundHandler.refundCapturedCancelledOrder(event.orderId(), event.paymentId());
                    return;
                }
                throw ex;
            }
        }
    }
}
