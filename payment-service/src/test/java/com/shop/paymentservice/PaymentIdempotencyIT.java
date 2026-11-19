package com.shop.paymentservice;

import com.shop.common.core.exception.BusinessException;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The idempotency contract of the payment create.
 *
 * <p>An idempotency key identifies the REQUEST, not the payment. Replaying the
 * payment of another request told the caller that it had created a payment for
 * its own order while the money was bound to a different one: the capture then
 * targeted the wrong payment, the confirm gate of the real order failed closed
 * and the paid order was auto-cancelled. A key reused with a different payload
 * must be a CONFLICT, never a silent replay.</p>
 */
class PaymentIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void theSameKeyWithTheSamePayloadReplaysTheSamePayment() {
        UUID orderId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        PaymentResponse first = paymentService.create(request(orderId, "100.00", key));
        PaymentResponse replay = paymentService.create(request(orderId, "100.00", key));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.orderId()).isEqualTo(orderId);
        assertThat(rowsFor(key)).hasSize(1);
    }

    @Test
    void theSameKeyWithAnotherOrderIsAConflict() {
        UUID orderId = UUID.randomUUID();
        UUID otherOrderId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        PaymentResponse first = paymentService.create(request(orderId, "100.00", key));

        assertThatThrownBy(() -> paymentService.create(request(otherOrderId, "100.00", key)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo("PAY-5003"));

        // the original payment is untouched
        Payment stored = paymentRepository.findById(first.id()).orElseThrow();
        assertThat(stored.getOrderId()).isEqualTo(orderId);
        assertThat(rowsFor(key)).hasSize(1);
    }

    @Test
    void theSameKeyWithAnotherAmountIsAConflict() {
        UUID orderId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        paymentService.create(request(orderId, "100.00", key));

        assertThatThrownBy(() -> paymentService.create(request(orderId, "250.50", key)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo("PAY-5003"));

        assertThat(rowsFor(key)).hasSize(1);
    }

    @Test
    void theSameKeyWithAnotherCurrencyIsAConflict() {
        UUID orderId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        paymentService.create(request(orderId, "100.00", key));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(
                orderId, new BigDecimal("100.00"), "USD", key)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * Two concurrent creates with the same key must not produce two payments -
     * and neither of them may see a raw constraint violation (a 500).
     */
    @Test
    void twoConcurrentCreatesWithTheSameKeyProduceOnePayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<PaymentResponse> call = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return paymentService.create(request(orderId, "100.00", key));
            };
            Future<PaymentResponse> first = pool.submit(call);
            Future<PaymentResponse> second = pool.submit(call);

            PaymentResponse a = first.get(30, TimeUnit.SECONDS);
            PaymentResponse b = second.get(30, TimeUnit.SECONDS);

            assertThat(a.id()).isEqualTo(b.id());
            assertThat(rowsFor(key)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Payment> rowsFor(String key) {
        return paymentRepository.findAll().stream()
                .filter(p -> key.equals(p.getIdempotencyKey()))
                .toList();
    }

    private static CreatePaymentRequest request(UUID orderId, String amount, String key) {
        return new CreatePaymentRequest(orderId, new BigDecimal(amount), "VND", key);
    }
}
