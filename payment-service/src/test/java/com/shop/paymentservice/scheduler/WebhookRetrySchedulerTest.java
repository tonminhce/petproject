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
        PaymentEvent event = newRetryable(5);

        doThrow(new RuntimeException("simulated downstream failure"))
            .when(webhookEventService).retry(any(PaymentEvent.class));
        when(eventRepository.save(any(PaymentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_PERMANENT);
        assertThat(event.getRetryCount()).isEqualTo(6);
        assertThat(event.getNextRetryAt()).isNotNull();
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("payment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void successfulReplayTransitionsToProcessedAndDoesNotIncrementCounter() {
        PaymentEvent event = newRetryable(1);

        doNothing().when(webhookEventService).retry(event);
        when(eventRepository.save(any(PaymentEvent.class))).thenAnswer(inv -> {
            PaymentEvent arg = inv.getArgument(0);
            if (!PaymentEvent.STATUS_PROCESSED.equals(arg.getStatus())) {
                arg.setStatus(PaymentEvent.STATUS_PROCESSED);
            }
            return arg;
        });

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(PaymentEvent.STATUS_PROCESSED);
        verify(eventRepository, atLeastOnce()).save(event);

        Double count = meterRegistry.find("payment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(0.0);
    }

    @Test
    void alreadyAtMaxAttemptsMarksFailedPermanentImmediately() {
        PaymentEvent event = newRetryable(6);

        when(eventRepository.save(any(PaymentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_PERMANENT);
        verify(webhookEventService, times(0)).retry(any(PaymentEvent.class));
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("payment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }
}
