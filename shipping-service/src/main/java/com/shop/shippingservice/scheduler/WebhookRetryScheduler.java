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

import java.time.Instant;
import java.util.List;

/**
 * C3 — retry failed shipping webhooks. See
 * {@code payment-service/.../scheduler/WebhookRetryScheduler.java} for the design
 * rationale; this scheduler mirrors it against the shipment_events table.
 *
 * <p>F2 — per-event transaction via {@link TransactionTemplate} (see sibling).
 * F3 — {@code shipment_webhook_failed_permanent_total} counter on terminal failures.</p>
 */
@Component
@Slf4j
public class WebhookRetryScheduler {

    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_SECONDS = 300L; // 5 minutes

    private final ShipmentEventRepository eventRepository;
    private final WebhookEventService webhookEventService;
    private final TransactionTemplate transactionTemplate;
    private final Counter failedPermanentCounter;

    @Value("${shop.shipping.webhook.retry-batch-size:50}")
    private int batchSize;

    public WebhookRetryScheduler(ShipmentEventRepository eventRepository,
                                 WebhookEventService webhookEventService,
                                 PlatformTransactionManager txManager,
                                 MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.webhookEventService = webhookEventService;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.failedPermanentCounter = meterRegistry.counter("shipment_webhook_failed_permanent_total");
    }

    @Scheduled(fixedDelayString = "${shop.shipping.webhook.retry-poll-ms:60000}")
    public void replay() {
        Instant now = Instant.now();
        List<ShipmentEvent> batch = eventRepository
                .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                    ShipmentEvent.STATUS_FAILED_RETRYABLE, now, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        log.info("Retrying {} shipment webhook event(s)", batch.size());
        for (ShipmentEvent event : batch) {
            replayOne(event);
        }
    }

    void replayOne(ShipmentEvent event) {
        transactionTemplate.execute(status -> {
            if (event.getRetryCount() >= MAX_ATTEMPTS) {
                event.setStatus(ShipmentEvent.STATUS_FAILED_PERMANENT);
                event.setLastError(truncate("Exceeded MAX_ATTEMPTS=" + MAX_ATTEMPTS, 1024));
                eventRepository.save(event);
                failedPermanentCounter.increment();
                log.warn("Shipment webhook {} permanently failed after {} retries",
                    event.getProviderEventId(), event.getRetryCount());
                return null;
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
            return null;
        });
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
