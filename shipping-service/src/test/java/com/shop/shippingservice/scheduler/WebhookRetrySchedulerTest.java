package com.shop.shippingservice.scheduler;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.entity.ShipmentEvent;
import com.shop.shippingservice.repository.ShipmentEventRepository;
import com.shop.shippingservice.service.WebhookEventService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F2/F3 — mirrors {@code payment-service/.../WebhookRetrySchedulerTest} for
 * the shipping side. See that class for design notes.
 */
@ExtendWith(MockitoExtension.class)
class WebhookRetrySchedulerTest {

    @Mock
    private ShipmentEventRepository eventRepository;

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

    private ShipmentEvent newRetryable(int retryCount) {
        return ShipmentEvent.builder()
                .id(UUID.randomUUID())
                .carrier(Carrier.NOOP)
                .providerEventId("evt-" + retryCount)
                .type("shipping.delivered.v1")
                .payload("{\"trackingNumber\":\"TN-1\",\"carrierStatus\":\"DELIVERED\"}")
                .status(ShipmentEvent.STATUS_FAILED_RETRYABLE)
                .retryCount(retryCount)
                .build();
    }

    @Test
    void exponentialBackoffStopsAtMaxAttemptsAndIncrementsCounter() {
        ShipmentEvent event = newRetryable(5);

        doThrow(new RuntimeException("simulated downstream failure"))
            .when(webhookEventService).retry(any(ShipmentEvent.class));
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(ShipmentEvent.STATUS_FAILED_PERMANENT);
        assertThat(event.getRetryCount()).isEqualTo(6);
        assertThat(event.getNextRetryAt()).isNotNull();
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("shipment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void successfulReplayTransitionsToProcessedAndDoesNotIncrementCounter() {
        ShipmentEvent event = newRetryable(1);

        doNothing().when(webhookEventService).retry(event);
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> {
            ShipmentEvent arg = inv.getArgument(0);
            if (!ShipmentEvent.STATUS_PROCESSED.equals(arg.getStatus())) {
                arg.setStatus(ShipmentEvent.STATUS_PROCESSED);
            }
            return arg;
        });

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(ShipmentEvent.STATUS_PROCESSED);
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("shipment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(0.0);
    }

    @Test
    void alreadyAtMaxAttemptsMarksFailedPermanentImmediately() {
        ShipmentEvent event = newRetryable(6);

        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event);

        assertThat(event.getStatus()).isEqualTo(ShipmentEvent.STATUS_FAILED_PERMANENT);
        verify(webhookEventService, times(0)).retry(any(ShipmentEvent.class));
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("shipment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }
}
