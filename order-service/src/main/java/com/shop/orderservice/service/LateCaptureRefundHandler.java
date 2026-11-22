package com.shop.orderservice.service;

import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.client.RefundOutcome;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LateCaptureRefundHandler {

    static final String REFERENCE_DOMAIN = "late-capture-refund";

    private final OrderRepository orderRepository;
    private final PaymentServiceClient paymentServiceClient;

    public void refundCapturedCancelledOrder(UUID orderId, UUID paymentId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.info("Order {} not found for the captured payment {} - ack-skipping the late-capture refund",
                    orderId, paymentId);
            return;
        }
        if (order.getStatus() != OrderStatus.CANCELLED) {
            log.info("Order {} is {} - the captured payment {} stays on the order lifecycle without a refund",
                    orderId, order.getStatus(), paymentId);
            return;
        }
        UUID refundReference = refundReference(orderId, paymentId);
        RefundOutcome outcome = paymentServiceClient.refundByOrderId(orderId, null, null, refundReference);
        log.info("The late capture {} of the cancelled order {} settled as {} under refund reference {}",
                paymentId, orderId, outcome, refundReference);
    }

    static UUID refundReference(UUID orderId, UUID paymentId) {
        String domain = REFERENCE_DOMAIN + ":" + orderId + ":" + paymentId;
        return UUID.nameUUIDFromBytes(domain.getBytes(StandardCharsets.UTF_8));
    }
}
