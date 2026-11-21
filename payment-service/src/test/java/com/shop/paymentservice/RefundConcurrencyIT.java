package com.shop.paymentservice;

import com.shop.common.core.exception.BusinessException;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.repository.PaymentRefundRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RefundConcurrencyIT extends AbstractIntegrationTest {

    private static final String SCRIPTED = "scripted";
    private static final BigDecimal PAYMENT_AMOUNT = new BigDecimal("100.00");
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("60.00");
    private static final long PROVIDER_DELAY_MILLIS = 800L;

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
    private ScriptedPaymentProvider scriptedProvider;

    @BeforeEach
    void resetScriptedProvider() {
        scriptedProvider.reset();
    }

    @Test
    @DisplayName("1. two refunds of the same payment running at the same time reach the provider only once")
    void twoConcurrentRefundsOfTheSamePaymentAreSerialised() throws Exception {
        UUID paymentId = createPayment();
        paymentService.capture(paymentId);
        scriptedProvider.scriptRefundDelay(PROVIDER_DELAY_MILLIS);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = pool.submit(() -> refundAfter(start, paymentId, UUID.randomUUID()));
            Future<Throwable> second = pool.submit(() -> refundAfter(start, paymentId, UUID.randomUUID()));
            start.countDown();

            List<Throwable> outcomes = new ArrayList<>();
            outcomes.add(first.get(60, TimeUnit.SECONDS));
            outcomes.add(second.get(60, TimeUnit.SECONDS));
            List<Throwable> failures = outcomes.stream()
                    .filter(Objects::nonNull)
                    .toList();

            assertThat(scriptedProvider.refundKeys())
                    .as("the payment row lock must stop the second refund before it reaches the provider")
                    .hasSize(1);
            assertThat(failures)
                    .as("the refund that exceeds the remaining refundable amount must be refused")
                    .hasSize(1);
            assertThat(failures.get(0)).isInstanceOf(BusinessException.class);
        } finally {
            pool.shutdownNow();
        }

        Payment row = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(row.getRefundedAmount()).isEqualByComparingTo(REFUND_AMOUNT);
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId))
                .as("only the refund that reached the provider is recorded")
                .hasSize(1);
    }

    @Test
    @DisplayName("2. a second partial refund of the same payment is paid and both refunds are recorded")
    void aSecondPartialRefundOfTheSamePaymentIsPaid() {
        UUID paymentId = createPayment();
        paymentService.capture(paymentId);
        UUID firstReference = UUID.randomUUID();
        UUID secondReference = UUID.randomUUID();

        paymentService.refund(paymentId, new BigDecimal("30.00"), "USD", firstReference);
        var second = paymentService.refund(paymentId, new BigDecimal("20.00"), "USD", secondReference);

        assertThat(second.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(second.previousStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(second.refundedAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(scriptedProvider.refundKeys())
                .as("every refund carries its own provider key")
                .containsExactly("refund-" + firstReference, "refund-" + secondReference);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId))
                .extracting(refund -> refund.getRefundReference())
                .containsExactlyInAnyOrder(firstReference.toString(), secondReference.toString());
    }

    @Test
    @DisplayName("3. the same approval is refunded once even when the provider replays the refund")
    void theSameApprovalIsRefundedOnlyOnce() {
        UUID paymentId = createPayment();
        paymentService.capture(paymentId);
        UUID reference = UUID.randomUUID();

        paymentService.refund(paymentId, new BigDecimal("30.00"), "USD", reference);
        var replay = paymentService.refund(paymentId, new BigDecimal("30.00"), "USD", reference);

        assertThat(replay.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(scriptedProvider.refundKeys()).hasSize(1);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId)).hasSize(1);
    }

    private Throwable refundAfter(CountDownLatch start, UUID paymentId, UUID reference) {
        try {
            start.await(30, TimeUnit.SECONDS);
            paymentService.refund(paymentId, REFUND_AMOUNT, "USD", reference);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ex;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    private UUID createPayment() {
        var created = paymentService.create(new CreatePaymentRequest(
                UUID.randomUUID(), PAYMENT_AMOUNT, "USD", UUID.randomUUID().toString(), null, SCRIPTED));
        return created.id();
    }
}
