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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookRetrySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final Instant STALE_BEFORE = NOW.minusSeconds(60);

    @Mock
    private ShipmentEventRepository eventRepository;

    @Mock
    private WebhookEventService webhookEventService;

    private MeterRegistry meterRegistry;
    private WebhookRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new WebhookRetryScheduler(eventRepository, webhookEventService,
                noOpTransactionManager(), meterRegistry, 60_000L);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "batchSize", 50);
    }

    private PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus(true);
            }
            @Override
            public void commit(TransactionStatus status) { }
            @Override
            public void rollback(TransactionStatus status) { }
        };
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
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(1);
        doThrow(new RuntimeException("simulated downstream failure"))
            .when(webhookEventService).retry(any(ShipmentEvent.class));
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event, NOW, STALE_BEFORE);

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
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(1);
        doNothing().when(webhookEventService).retry(event);
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> {
            ShipmentEvent arg = inv.getArgument(0);
            if (!ShipmentEvent.STATUS_PROCESSED.equals(arg.getStatus())) {
                arg.setStatus(ShipmentEvent.STATUS_PROCESSED);
            }
            return arg;
        });

        scheduler.replayOne(event, NOW, STALE_BEFORE);

        assertThat(event.getStatus()).isEqualTo(ShipmentEvent.STATUS_PROCESSED);
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("shipment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(0.0);
    }

    @Test
    void alreadyAtMaxAttemptsMarksFailedPermanentImmediately() {
        ShipmentEvent event = newRetryable(6);
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(1);
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event, NOW, STALE_BEFORE);

        assertThat(event.getStatus()).isEqualTo(ShipmentEvent.STATUS_FAILED_PERMANENT);
        verify(webhookEventService, times(0)).retry(any(ShipmentEvent.class));
        verify(eventRepository, times(1)).save(event);

        Double count = meterRegistry.find("shipment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void anEventAlreadyClaimedByAnotherWorker_isNotProcessed() {
        ShipmentEvent event = newRetryable(1);
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(0);

        scheduler.replayOne(event, NOW, STALE_BEFORE);

        verify(webhookEventService, never()).retry(any(ShipmentEvent.class));
        verify(eventRepository, never()).save(any(ShipmentEvent.class));
    }

    @Test
    void theClaimIsReleasedAfterTheAttempt() {
        ShipmentEvent event = newRetryable(1);
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(1);
        doNothing().when(webhookEventService).retry(event);
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.replayOne(event, NOW, STALE_BEFORE);

        verify(eventRepository).releaseClaim(event.getId());
    }

    @Test
    void aFailureAtCommitTime_advancesTheRetryCounter() {
        ShipmentEvent event = newRetryable(1);
        ShipmentEvent stored = newRetryable(1);
        WebhookRetryScheduler failing = new WebhookRetryScheduler(eventRepository, webhookEventService,
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus(true);
                    }
                    @Override
                    public void commit(TransactionStatus status) {
                        throw new IllegalStateException("optimistic lock failure at commit");
                    }
                    @Override
                    public void rollback(TransactionStatus status) { }
                }, meterRegistry, 60_000L);
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(1);
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(stored));
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        failing.replayOne(event, NOW, STALE_BEFORE);

        assertThat(stored.getRetryCount()).isEqualTo(2);
        assertThat(stored.getNextRetryAt()).isNotNull();
        assertThat(stored.getLastError()).isNotBlank();
        verify(eventRepository, times(1)).save(stored);
    }

    @Test
    void theBatchIsReadThroughTheClaimAwareQuery() {
        when(eventRepository.findClaimableByStatus(eq(ShipmentEvent.STATUS_FAILED_RETRYABLE),
                any(Instant.class), any(Instant.class), any()))
                .thenReturn(java.util.List.of());

        scheduler.replay();

        verify(eventRepository).findClaimableByStatus(eq(ShipmentEvent.STATUS_FAILED_RETRYABLE),
                any(Instant.class), any(Instant.class), any());
        verify(eventRepository, never()).claimForRetry(any(), any(), any(), any());
        verify(eventRepository, never()).save(any(ShipmentEvent.class));
    }

    @Test
    void anEventThatFailsAtCommitTimeTwice_reachesThePermanentState() {
        ShipmentEvent event = newRetryable(5);
        ShipmentEvent stored = newRetryable(5);
        WebhookRetryScheduler failing = new WebhookRetryScheduler(eventRepository, webhookEventService,
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus(true);
                    }
                    @Override
                    public void commit(TransactionStatus status) {
                        throw new IllegalStateException("optimistic lock failure at commit");
                    }
                    @Override
                    public void rollback(TransactionStatus status) { }
                }, meterRegistry, 60_000L);
        when(eventRepository.claimForRetry(eq(event.getId()),
                eq(ShipmentEvent.STATUS_FAILED_RETRYABLE), eq(NOW), eq(STALE_BEFORE))).thenReturn(1);
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(stored));
        when(eventRepository.save(any(ShipmentEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        failing.replayOne(event, NOW, STALE_BEFORE);

        assertThat(stored.getStatus()).isEqualTo(ShipmentEvent.STATUS_FAILED_PERMANENT);
        assertThat(stored.getRetryCount()).isEqualTo(6);
        Double count = meterRegistry.find("shipment_webhook_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }
}
