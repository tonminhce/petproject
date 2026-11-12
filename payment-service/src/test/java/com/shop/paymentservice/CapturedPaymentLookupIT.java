package com.shop.paymentservice;

import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The captured-payment lookup the order-service confirm gate depends on.
 *
 * <p>The gate used to read ONE PAGE of the paged list endpoint, so a captured
 * payment that sat beyond that page was invisible: the gate failed closed
 * (ORD-4012), the order could never be confirmed and was auto-cancelled later
 * - a customer who had paid lost the order. The lookup is now authoritative,
 * and this IT pins the difference down against a real database: the captured
 * payment is the OLDEST one of the order, so it is NOT on the first page of
 * the list.</p>
 */
class CapturedPaymentLookupIT extends AbstractIntegrationTest {

    private static final int PAGE_SIZE = 10;

    @Autowired
    private PaymentService paymentService;

    @Test
    void theCapturedPaymentIsFoundEvenWhenItIsBeyondTheFirstPage() {
        UUID orderId = UUID.randomUUID();

        // the captured payment is the OLDEST payment of the order
        UUID capturedId = createPayment(orderId);
        paymentService.capture(capturedId);
        for (int i = 0; i < 12; i++) {
            createPayment(orderId);
        }

        var found = paymentService.findCapturedByOrderId(orderId);

        assertThat(found).as("the captured payment must be found wherever it sits in the order").isPresent();
        assertThat(found.get().id()).isEqualTo(capturedId);
        assertThat(found.get().status().name()).isEqualTo("CAPTURED");

        // and the paged list the gate used to read really does hide it
        var firstPage = paymentService.findAllByOrderId(orderId, PageRequest.of(0, PAGE_SIZE));
        assertThat(firstPage.getTotalElements()).isEqualTo(13);
        assertThat(firstPage.getContent())
                .extracting(PaymentResponse::id)
                .doesNotContain(capturedId);
    }

    @Test
    void anOrderWithoutACapturedPaymentHasNone() {
        UUID orderId = UUID.randomUUID();
        createPayment(orderId);

        assertThat(paymentService.findCapturedByOrderId(orderId)).isEmpty();
    }

    @Test
    void anOrderWithoutAnyPaymentHasNone() {
        assertThat(paymentService.findCapturedByOrderId(UUID.randomUUID())).isEmpty();
    }

    private UUID createPayment(UUID orderId) {
        PaymentResponse payment = paymentService.create(new CreatePaymentRequest(
                orderId, new BigDecimal("100.00"), "USD", UUID.randomUUID().toString()));
        return payment.id();
    }
}
