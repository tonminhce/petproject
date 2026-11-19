package com.shop.shippingservice.scheduler;

import com.shop.shippingservice.entity.ShipmentEvent;
import com.shop.shippingservice.repository.ShipmentEventRepository;
import com.shop.shippingservice.service.WebhookEventService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class WebhookRetryScheduler {

    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_SECONDS = 300L;

    private final ShipmentEventRepository eventRepository;
    private final WebhookEventService webhookEventService;
    private final TransactionTemplate transactionTemplate;
    private final Counter failedPermanentCounter;
    private final long claimLeaseMs;

    @Value("${shop.shipping.webhook.retry-batch-size:50}")
    private int batchSize;

    public WebhookRetryScheduler(ShipmentEventRepository eventRepository,
                                 WebhookEventService webhookEventService,
                                 PlatformTransactionManager txManager,
                                 MeterRegistry meterRegistry,
                                 @Value("${shop.shipping.webhook.claim-lease-ms:60000}") long claimLeaseMs) {
        this.eventRepository = eventRepository;
        this.webhookEventService = webhookEventService;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.failedPermanentCounter = meterRegistry.counter("shipment_webhook_failed_permanent_total");
        this.claimLeaseMs = claimLeaseMs;
    }

    @Scheduled(fixedDelayString = "${shop.shipping.webhook.retry-poll-ms:60000}")
    public void replay() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofMillis(claimLeaseMs));
        List<ShipmentEvent> batch = eventRepository.findClaimableByStatus(
                ShipmentEvent.STATUS_FAILED_RETRYABLE, now, staleBefore, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        log.info("Retrying {} shipment webhook event(s)", batch.size());
        for (ShipmentEvent event : batch) {
            try {
                replayOne(event, now, staleBefore);
            } catch (RuntimeException e) {
                log.error("Shipment webhook {} replay failed outside the transaction boundary",
                        event.getProviderEventId(), e);
            }
        }
    }

    void replayOne(ShipmentEvent event, Instant now, Instant staleBefore) {
        int claimed = eventRepository.claimForRetry(event.getId(),
                ShipmentEvent.STATUS_FAILED_RETRYABLE, now, staleBefore);
        if (claimed == 0) {
            log.info("Shipment webhook {} is already claimed by another worker", event.getProviderEventId());
            return;
        }
        try {
            transactionTemplate.execute(status -> {
                attempt(event);
                return null;
            });
        } catch (RuntimeException e) {
            recordCommitFailure(event, e);
        } finally {
            releaseClaim(event);
        }
    }

    private void attempt(ShipmentEvent event) {
        if (event.getRetryCount() >= MAX_ATTEMPTS) {
            event.setStatus(ShipmentEvent.STATUS_FAILED_PERMANENT);
            event.setLastError(truncate("Exceeded MAX_ATTEMPTS=" + MAX_ATTEMPTS, 1024));
            eventRepository.save(event);
            failedPermanentCounter.increment();
            log.warn("Shipment webhook {} permanently failed after {} retries",
                    event.getProviderEventId(), event.getRetryCount());
            return;
        }
        int attempt = event.getRetryCount() + 1;
        try {
            webhookEventService.retry(event);
            if (!ShipmentEvent.STATUS_PROCESSED.equals(event.getStatus())) {
                event.setStatus(ShipmentEvent.STATUS_PROCESSED);
                event.setLastError(null);
                eventRepository.save(event);
            }
            log.info("Shipment webhook {} replay succeeded on attempt {}", event.getProviderEventId(), attempt);
        } catch (Exception e) {
            event.setRetryCount(attempt);
            event.setLastError(truncate(e.getMessage(), 1024));
            event.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds(attempt)));
            if (attempt >= MAX_ATTEMPTS) {
                event.setStatus(ShipmentEvent.STATUS_FAILED_PERMANENT);
                failedPermanentCounter.increment();
                log.error("Shipment webhook {} permanently failed after {} retries",
                        event.getProviderEventId(), attempt, e);
            } else {
                log.warn("Shipment webhook {} retry {}/{} failed; next at {}",
                        event.getProviderEventId(), attempt, MAX_ATTEMPTS, event.getNextRetryAt(), e);
            }
            eventRepository.save(event);
        }
    }

    private void recordCommitFailure(ShipmentEvent event, RuntimeException failure) {
        try {
            ShipmentEvent current = eventRepository.findById(event.getId()).orElse(null);
            if (current == null) {
                return;
            }
            int attempt = current.getRetryCount() + 1;
            current.setRetryCount(attempt);
            current.setLastError(truncate(failure.getMessage(), 1024));
            current.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds(attempt)));
            if (attempt >= MAX_ATTEMPTS) {
                current.setStatus(ShipmentEvent.STATUS_FAILED_PERMANENT);
                failedPermanentCounter.increment();
            }
            eventRepository.save(current);
            log.warn("Shipment webhook {} failed at commit time; retry {}/{} recorded",
                    event.getProviderEventId(), attempt, MAX_ATTEMPTS);
        } catch (RuntimeException e) {
            log.error("Shipment webhook {} commit failure could not be recorded",
                    event.getProviderEventId(), e);
        }
    }

    private void releaseClaim(ShipmentEvent event) {
        try {
            eventRepository.releaseClaim(event.getId());
        } catch (RuntimeException e) {
            log.warn("Shipment webhook {} claim could not be released", event.getProviderEventId(), e);
        }
    }

    static long backoffSeconds(int attempt) {
        long mult = 1;
        for (int i = 1; i < attempt; i++) {
            mult *= 5L;
        }
        return BASE_BACKOFF_SECONDS * mult;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
