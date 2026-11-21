package com.shop.paymentservice;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.outbox.OutboxEvent;
import com.shop.paymentservice.outbox.OutboxEventRepository;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import com.shop.paymentservice.support.ScriptedPaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCaptureConfirmationIT extends AbstractIntegrationTest {

    private static final String SCRIPTED = "scripted";
    private static final String CAPTURE_EVENT = "payment.captured.v1";
    private static final String REFUND_EVENT = "payment.refunded.v1";

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
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ScriptedPaymentProvider scriptedProvider;

    @BeforeEach
    void resetScriptedProvider() {
        scriptedProvider.reset();
    }

    @Test
    @DisplayName("1. confirmed provider result captures the payment and publishes exactly one capture event")
    void confirmedCapturePersistsCapturedAndPublishesExactlyOneCaptureEvent() {
        scriptedProvider.scriptCapture(PaymentProvider.ProviderResult.confirmed("pi_scripted_confirmed"));

        UUID paymentId = createPayment(SCRIPTED);
        PaymentResponse response = paymentService.capture(paymentId);

        assertThat(response.status()).isEqualTo(PaymentStatus.CAPTURED);
        Payment row = reload(paymentId);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(row.getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row.getProviderIntentId()).isEqualTo("pi_scripted_confirmed");
        assertThat(events(paymentId, CAPTURE_EVENT)).hasSize(1);
    }

    @Test
    @DisplayName("2. a refund through the owning provider reaches REFUNDED and publishes one refund event")
    void refundThroughTheOwningProviderReachesRefunded() {
        scriptedProvider.scriptCapture(PaymentProvider.ProviderResult.confirmed("pi_scripted_refund"));
        UUID paymentId = createPayment(SCRIPTED);
        paymentService.capture(paymentId);

        PaymentResponse response = paymentService.refund(paymentId, new BigDecimal("100.00"), "USD",
                UUID.randomUUID());

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        Payment row = reload(paymentId);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(row.getPreviousStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(events(paymentId, REFUND_EVENT)).hasSize(1);
        assertThat(scriptedProvider.refundCalls()).containsExactly(paymentId + ":USD:pi_scripted_refund");
    }

    @Test
    @DisplayName("3. a REQUIRES_CONFIRMATION result keeps the payment PENDING, stores intent id and client secret, publishes no capture event")
    void requiresConfirmationKeepsPaymentPendingAndStoresTheClientSecret() {
        scriptedProvider.scriptCapture(
                PaymentProvider.ProviderResult.requiresConfirmation("pi_scripted_pending", "pi_secret_scripted"));

        UUID paymentId = createPayment(SCRIPTED);
        PaymentResponse response = paymentService.capture(paymentId);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.providerIntentId()).isEqualTo("pi_scripted_pending");
        assertThat(response.clientSecret()).isEqualTo("pi_secret_scripted");
        Payment row = reload(paymentId);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row.getPreviousStatus()).isNull();
        assertThat(row.getProviderIntentId()).isEqualTo("pi_scripted_pending");
        assertThat(row.getClientSecret()).isEqualTo("pi_secret_scripted");
        assertThat(events(paymentId, CAPTURE_EVENT)).isEmpty();
    }

    @Test
    @DisplayName("4. an unknown provider name is rejected with PAY-5008 and never falls back to the default provider")
    void captureOfAnUnregisteredProviderIsRejectedAndDoesNotFallBack() {
        UUID paymentId = createPayment(SCRIPTED);
        setProvider(paymentId, "absent-psp");

        assertThatThrownBy(() -> paymentService.capture(paymentId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));

        Payment row = reload(paymentId);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row.getProviderIntentId()).isNull();
        assertThat(events(paymentId, CAPTURE_EVENT)).isEmpty();
    }

    @Test
    @DisplayName("5. an INDETERMINATE provider result raises a retryable error and marks the payment neither failed nor captured")
    void indeterminateCaptureRaisesARetryableErrorAndLeavesThePaymentPending() {
        scriptedProvider.scriptCapture(PaymentProvider.ProviderResult.indeterminate("stripe-timeout"));

        UUID paymentId = createPayment(SCRIPTED);

        assertThatThrownBy(() -> paymentService.capture(paymentId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException failure = (BusinessException) e;
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        Payment row = reload(paymentId);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row.getStatus()).isNotEqualTo(PaymentStatus.FAILED);
        assertThat(row.getStatus()).isNotEqualTo(PaymentStatus.CAPTURED);
        assertThat(events(paymentId, CAPTURE_EVENT)).isEmpty();
    }

    @Test
    @DisplayName("6. the default mock provider cannot silently back a payment owned by another provider")
    void theDefaultMockProviderCannotBackAPaymentOfAnotherProvider() {
        scriptedProvider.scriptCapture(PaymentProvider.ProviderResult.confirmed("pi_scripted_owned"));
        UUID paymentId = createPayment(SCRIPTED);
        paymentService.capture(paymentId);
        setProvider(paymentId, "stripe");

        assertThatThrownBy(() -> paymentService.refund(paymentId, new BigDecimal("100.00"), "USD",
                UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));

        Payment row = reload(paymentId);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(row.getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(events(paymentId, REFUND_EVENT)).isEmpty();
        assertThat(scriptedProvider.refundCalls()).isEmpty();
    }

    private UUID createPayment(String provider) {
        PaymentResponse created = paymentService.create(new CreatePaymentRequest(
                UUID.randomUUID(), new BigDecimal("100.00"), "USD", UUID.randomUUID().toString(), null, provider));
        return created.id();
    }

    private void setProvider(UUID paymentId, String provider) {
        Payment row = reload(paymentId);
        row.setProvider(provider);
        paymentRepository.saveAndFlush(row);
    }

    private Payment reload(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private List<OutboxEvent> events(UUID paymentId, String eventType) {
        return outboxRepository.findAll().stream()
                .filter(event -> paymentId.equals(event.getAggregateId()) && eventType.equals(event.getEventType()))
                .toList();
    }
}
