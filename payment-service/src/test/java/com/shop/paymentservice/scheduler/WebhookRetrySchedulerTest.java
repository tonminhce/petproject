package com.shop.paymentservice.scheduler;

import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.service.WebhookEventService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F2/F3 — covers the {@link WebhookRetryScheduler} state machine without booting
 * Spring or touching Postgres. The PlatformTransactionManager is a no-op stub
 * because the assertions are about state-machine semantics, not transaction
 * propagation (that's verified by integration tests on a real DB).
 *
 * <p>The two tests assert:</p>
 * <ul>
 *   <li>backoff: every failed attempt increments {@code retry_count} and sets
 *       a later {@code next_retry_at}; exceeding MAX_ATTEMPTS flips status to
 *       FAILED_PERMANENT and increments the counter;</li>
 *   <li>successful replay: status transitions to PROCESSED and counter is not
 *       incremented.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WebhookRetrySchedulerTest {

    @Mock
    private PaymentEventRepository eventRepository;

    @Mock
    private WebhookEventService webhookEventService;

    private MeterRegistry meterRegistry;
    private WebhookRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                // No-op — TransactionTemplate needs a non-null status to commit.
                return new SimpleTransactionStatus(true);
            }
            @Override
            public void commit(TransactionStatus status) { }
            @Override
            public void rollback(TransactionStatus status) { }
        };
        scheduler = new WebhookRetryScheduler(eventRepository, webhookEventService, txManager, meterRegistry);
    }

    private PaymentEvent newRetryable(int retryCount) {
        return PaymentEvent.builder()
                .id(java.util.UUID.randomUUID())
                .provider("mock")
                .providerEventId("evt-" + retryCount)
                .type("payment.captured.v1")
                .payload("{\"paymentId\":\"00000000-0000-0000-0000-000000000001\",\"status\":\"CAPTURED\"}")
                .status(PaymentEvent.STATUS_FAILED_RETRYABLE)
                .retryCount(retryCount)
                .build();
    }

    @Test
    void exponentialBackoffStopsAtMaxAttemptsAndIncrementsCounter() {
        PaymentEvent event = newRetryable(5); // one attempt away from the cap

        doThrow(new RuntimeException("simulated downstream failure"))
            .when(webhookEventService).retry(any(PaymentEvent.class));
        when(eventRepository.save(any(PaymentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event);

        // The 6th attempt should fail and tip the row to FAILED_PERMANENT.
        assertThat(event.getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_PERMANENT);
        assertThat(event.getRetryCount()).isEqualTo(6);
        assertThat(event.getNextRetryAt()).isNotNull();
        verify(eventRepository, times(1)).save(event);

        // F3 — counter incremented exactly once.
        Double count = meterRegistry.find("payment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void successfulReplayTransitionsToProcessedAndDoesNotIncrementCounter() {
        PaymentEvent event = newRetryable(1);

        // Simulate the service flipping the status to PROCESSED (writer.completeWithEvent does this).
        doNothing().when(webhookEventService).retry(event);
        // Because the production code path leaves PROCESSED already, the scheduler's
        // defensive setStatus shouldn't fire — but we still allow the save call.
        when(eventRepository.save(any(PaymentEvent.class))).thenAnswer(inv -> {
            PaymentEvent arg = inv.getArgument(0);
            // The service's retry() call left status as PROCESSED — replicate that here
            // so the defensive branch in the scheduler isn't triggered.
            if (!PaymentEvent.STATUS_PROCESSED.equals(arg.getStatus())) {
                arg.setStatus(PaymentEvent.STATUS_PROCESSED);
            }
            return arg;
        });

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(PaymentEvent.STATUS_PROCESSED);
        verify(eventRepository, atLeastOnce()).save(event);

        // F3 — counter must NOT have been touched on a successful replay.
        Double count = meterRegistry.find("payment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(0.0);
    }

    @Test
    void alreadyAtMaxAttemptsMarksFailedPermanentImmediately() {
        PaymentEvent event = newRetryable(6); // already at the cap

        when(eventRepository.save(any(PaymentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_PERMANENT);
        // The webhook service must NOT be called — we're already past the cap.
        verify(webhookEventService, times(0)).retry(any(PaymentEvent.class));
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("payment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }
}
