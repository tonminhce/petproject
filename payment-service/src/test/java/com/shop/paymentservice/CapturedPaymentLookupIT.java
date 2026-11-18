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

class CapturedPaymentLookupIT extends AbstractIntegrationTest {

    private static final int PAGE_SIZE = 10;

    @Autowired
    private PaymentService paymentService;

    @Test
    void theCapturedPaymentIsFoundEvenWhenItIsBeyondTheFirstPage() {
        UUID orderId = UUID.randomUUID();

        UUID capturedId = createPayment(orderId);
        paymentService.capture(capturedId);
        for (int i = 0; i < 12; i++) {
            createPayment(orderId);
        }

        var found = paymentService.findCapturedByOrderId(orderId);

        assertThat(found).as("the captured payment must be found wherever it sits in the order").isPresent();
        assertThat(found.get().id()).isEqualTo(capturedId);
        assertThat(found.get().status().name()).isEqualTo("CAPTURED");

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
