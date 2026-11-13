package com.shop.orderservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.orderservice.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class PaymentLifecycleConsumer extends BaseKafkaConsumer<String, PaymentLifecycleEvent> {

    private final OrderService orderService;

    public PaymentLifecycleConsumer(OrderService orderService) {
        this.orderService = orderService;
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
        if (event != null && "payment.captured.v1".equals(event.eventType())) {
            log.info("Processing payment.captured.v1 for orderId={}, paymentId={}", event.orderId(), event.paymentId());
            try {
                orderService.confirmOrder(event.orderId(), "system:payment-consumer", "payment-confirm-" + event.paymentId());
            } catch (BusinessException ex) {
                if (ErrorCode.ORDER_INVALID_STATE_TRANSITION.getCode().equals(ex.getErrorCode())) {
                    log.info("Order {} already transitioned beyond PENDING — skipping redundant payment confirmation", event.orderId());
                    return;
                }
                throw ex;
            }
        }
    }
}
