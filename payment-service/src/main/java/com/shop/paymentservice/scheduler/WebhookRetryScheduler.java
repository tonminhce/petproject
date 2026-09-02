package com.shop.paymentservice.scheduler;

import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.service.WebhookEventService;
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
 * C3 — replay failed payment webhooks. The previous design had no retry path:
 * once an event was marked FAILED its state was terminal. This scheduler walks
 * the {@code FAILED_RETRYABLE} queue and re-invokes {@link WebhookEventService#retry}
 * for each event whose {@code next_retry_at} has elapsed.
 *
 * <p>Backoff: {@code 5min × 5^(retryCount)} so the 6 attempts land at roughly
 * 5m, 25m, 2h, 8h, 22h, 4d. Exceeding {@code MAX_ATTEMPTS} transitions the
 * row to {@code FAILED_PERMANENT} — a state ops can detect and investigate.</p>
 *
 * <p>F2 — per-event transaction via {@link TransactionTemplate} (not
 * {@code @Transactional}): {@code replayOne} is called from {@link #replay()} via
 * self-invocation, which bypasses Spring's proxy and silently disables
 * {@code @Transactional}. Wrapping the body in {@code transactionTemplate.execute(...)}
 * gives a real per-event tx boundary: a failure rolls back only that row's update,
 * siblings keep their progress.</p>
 *
 * <p>F3 — {@code payment_webhook_failed_permanent_total} counter increments on
 * terminal failures so ops can alert on it.</p>
 */
@Component
@Slf4j
public class WebhookRetryScheduler {

    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_SECONDS = 300L; // 5 minutes

    private final PaymentEventRepository eventRepository;
    private final WebhookEventService webhookEventService;
    private final TransactionTemplate transactionTemplate;
    private final Counter failedPermanentCounter;

    @Value("${shop.payment.webhook.retry-batch-size:50}")
    private int batchSize;

    public WebhookRetryScheduler(PaymentEventRepository eventRepository,
                                 WebhookEventService webhookEventService,
                                 PlatformTransactionManager txManager,
                                 MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.webhookEventService = webhookEventService;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.failedPermanentCounter = meterRegistry.counter("payment_webhook_failed_permanent_total");
    }

    @Scheduled(fixedDelayString = "${shop.payment.webhook.retry-poll-ms:60000}")
    public void replay() {
        Instant now = Instant.now();
        List<PaymentEvent> batch = eventRepository
                .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                    PaymentEvent.STATUS_FAILED_RETRYABLE, now, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        log.info("Retrying {} webhook event(s)", batch.size());
        for (PaymentEvent event : batch) {
            replayOne(event);
        }
    }

    void replayOne(PaymentEvent event) {
        // F2 — real per-event tx boundary (self-invocation bypasses @Transactional).
        transactionTemplate.execute(status -> {
            if (event.getRetryCount() >= MAX_ATTEMPTS) {
                event.setStatus(PaymentEvent.STATUS_FAILED_PERMANENT);
                event.setLastError(truncate("Exceeded MAX_ATTEMPTS=" + MAX_ATTEMPTS, 1024));
                eventRepository.save(event);
                failedPermanentCounter.increment();
                log.warn("Payment webhook {} permanently failed after {} retries",
                    event.getProviderEventId(), event.getRetryCount());
                return null;
            }
            int attempt = event.getRetryCount() + 1;
            try {
                webhookEventService.retry(event);
                // On success `retry()` raises nothing and process() set status=PROCESSED via completeWithEvent.
                // Defensive: if a quirk left the row not PROCESSED, mark it now.
                if (!PaymentEvent.STATUS_PROCESSED.equals(event.getStatus())) {
                    event.setStatus(PaymentEvent.STATUS_PROCESSED);
                    event.setLastError(null);
                    eventRepository.save(event);
                }
                log.info("Payment webhook {} replay succeeded on attempt {}", event.getProviderEventId(), attempt);
            } catch (Exception e) {
                event.setRetryCount(attempt);
                event.setLastError(truncate(e.getMessage(), 1024));
                event.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds(attempt)));
                if (attempt >= MAX_ATTEMPTS) {
                    event.setStatus(PaymentEvent.STATUS_FAILED_PERMANENT);
                    failedPermanentCounter.increment();
                    log.error("Payment webhook {} permanently failed after {} retries",
                        event.getProviderEventId(), attempt, e);
                } else {
                    log.warn("Payment webhook {} retry {}/{} failed; next at {}",
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
