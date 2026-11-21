package com.shop.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentRefund;
import com.shop.paymentservice.repository.PaymentRefundRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.service.WebhookEventService;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import com.shop.paymentservice.support.ScriptedPaymentProvider;
import com.shop.paymentservice.webhook.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookRefundLedgerIT extends AbstractIntegrationTest {

    private static final String SCRIPTED = "scripted";
    private static final String STRIPE = "stripe";
    private static final BigDecimal PAYMENT_AMOUNT = new BigDecimal("100.00");
    private static final BigDecimal PLATFORM_REFUND = new BigDecimal("30.00");

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedProviderConfig {

        @Bean
        ScriptedPaymentProvider scriptedPaymentProvider() {
            return new ScriptedPaymentProvider();
        }
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentRefundRepository paymentRefundRepository;

    @Autowired
    private WebhookEventService webhookEventService;

    @Autowired
    private ScriptedPaymentProvider scriptedProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetScriptedProvider() {
        scriptedProvider.reset();
    }

    @Test
    @DisplayName("1. the provider event that confirms the refund the platform already paid is not counted twice")
    void theProviderEventThatConfirmsThePlatformRefundIsNotCountedTwice() {
        UUID paymentId = capturedPayment();
        paymentService.refund(paymentId, PLATFORM_REFUND, "USD", UUID.randomUUID());

        deliverProviderRefund(paymentId, "evt_provider_refund_1", PLATFORM_REFUND);

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getRefundedAmount())
                .as("the provider reports the cumulative refunded amount that the platform already paid")
                .isEqualByComparingTo("30.00");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId))
                .as("the platform refund is the only confirmed refund of the ledger")
                .hasSize(1);
    }

    @Test
    @DisplayName("2. the provider event that refunds more than the platform recorded adds only the difference")
    void theProviderEventThatRefundsMoreThanThePlatformRecordedAddsOnlyTheDifference() {
        UUID paymentId = capturedPayment();
        paymentService.refund(paymentId, PLATFORM_REFUND, "USD", UUID.randomUUID());

        deliverProviderRefund(paymentId, "evt_provider_refund_2", new BigDecimal("50.00"));

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getRefundedAmount()).isEqualByComparingTo("50.00");
        List<PaymentRefund> ledger = paymentRefundRepository.findByPaymentId(paymentId);
        assertThat(ledger).hasSize(2);
        assertThat(ledger.stream().map(PaymentRefund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the ledger holds the confirmed refunds of the platform and of the provider")
                .isEqualByComparingTo("50.00");
        assertThat(ledger.stream()
                .filter(row -> "evt_provider_refund_2".equals(row.getProviderEventId()))
                .map(PaymentRefund::getAmount)
                .findFirst()
                .orElseThrow())
                .as("the provider row holds the difference and not the cumulative amount")
                .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("3. the provider event that refunds the rest of the payment closes the payment")
    void theProviderEventThatRefundsTheRestOfThePaymentClosesThePayment() {
        UUID paymentId = capturedPayment();
        paymentService.refund(paymentId, PLATFORM_REFUND, "USD", UUID.randomUUID());

        deliverProviderRefund(paymentId, "evt_provider_refund_3", PAYMENT_AMOUNT);

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getRefundedAmount()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId)).hasSize(2);
    }

    @Test
    @DisplayName("4. a provider refund the platform never paid is recorded in the ledger")
    void aProviderRefundThePlatformNeverPaidIsRecordedInTheLedger() {
        UUID paymentId = capturedPayment();

        deliverProviderRefund(paymentId, "evt_provider_refund_4", new BigDecimal("25.00"));

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getRefundedAmount()).isEqualByComparingTo("25.00");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId)).hasSize(1);
    }

    private UUID capturedPayment() {
        var created = paymentService.create(new CreatePaymentRequest(
                UUID.randomUUID(), PAYMENT_AMOUNT, "USD", "it-webhook-" + UUID.randomUUID(), null, SCRIPTED));
        paymentService.capture(created.id());
        return created.id();
    }

    private void deliverProviderRefund(UUID paymentId, String providerEventId, BigDecimal providerCumulativeAmount) {
        WebhookPayload payload = WebhookPayload.builder()
                .eventId(providerEventId)
                .eventType("charge.refunded")
                .paymentId(paymentId.toString())
                .amount(providerCumulativeAmount)
                .currency("USD")
                .status("PARTIALLY_REFUNDED")
                .providerEventId(providerEventId)
                .build();
        webhookEventService.handle(STRIPE, carrier(payload));
    }

    private byte[] carrier(WebhookPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
