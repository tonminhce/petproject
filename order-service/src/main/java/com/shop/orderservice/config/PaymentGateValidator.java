package com.shop.orderservice.config;

import com.shop.orderservice.client.PaymentServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentGateValidator {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateValidator.class);

    private final PaymentServiceClient paymentClient;
    private final boolean gateRequired;

    public PaymentGateValidator(
            PaymentServiceClient paymentClient,
            @Value("${order.payment-gate.require-captured-payment:true}") boolean gateRequired) {
        this.paymentClient = paymentClient;
        this.gateRequired = gateRequired;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        boolean paymentEnabled = paymentClient.isEnabled();
        if (paymentEnabled && gateRequired) {
            log.info("Payment gate active: confirm requires a CAPTURED payment "
                    + "(shop.services.payment.enabled=true, order.payment-gate.require-captured-payment=true)");
            return;
        }
        if (paymentEnabled) {
            log.warn("order.payment-gate.require-captured-payment=false - an order can be CONFIRMED "
                    + "without a captured payment and an admin cancel will not trigger a refund.");
            return;
        }
        if (gateRequired) {
            throw new IllegalStateException(
                    "shop.services.payment.enabled is false while order.payment-gate.require-captured-payment "
                    + "is true. The payment-captured confirm gate would be silently disabled: an order could "
                    + "reach CONFIRMED with no captured payment, and an admin cancel of a CONFIRMED order would "
                    + "not trigger a refund. Enable the payment integration (PAYMENT_SERVICE_ENABLED=true) or "
                    + "explicitly set order.payment-gate.require-captured-payment=false to accept that risk.");
        }
        log.warn("Payment integration is DISABLED and the captured-payment gate is not required - orders can be "
                + "confirmed without payment and cancellations will not be refunded.");
    }
}
