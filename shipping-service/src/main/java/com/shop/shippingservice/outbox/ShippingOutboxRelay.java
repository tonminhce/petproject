package com.shop.shippingservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.kafka.producer.OutboxBatchOrdering;
import com.shop.common.kafka.producer.OutboxPublishLedger;
import com.shop.shippingservice.outbox.OutboxEvent;
import com.shop.shippingservice.outbox.OutboxEventRepository;
import com.shop.shippingservice.service.ShippingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ShippingOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ShippingMetrics metrics;
    private final OutboxPublishLedger publishLedger = new OutboxPublishLedger();

    @Value("${shop.shipping.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.shipping.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${shop.shipping.outbox.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    @Value("${shop.shipping.outbox.claim-lease-ms:60000}")
    private long claimLeaseMs;

    private static final int MAX_ERROR_LENGTH = 1000;

    public ShippingOutboxRelay(OutboxEventRepository outboxRepo,
                       KafkaMessagePublisher kafkaPublisher,
                       PlatformTransactionManager txManager,
                       ShippingMetrics metrics) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${shop.shipping.outbox.poll-millis:2000}",
            initialDelayString = "${shop.shipping.outbox.poll-millis:2000}")
    public void relay() {
        List<Long> claimedIds = claimPendingBatch();
        if (claimedIds.isEmpty()) {
            return;
        }

        List<OutboxEvent> rows = orderById(new ArrayList<>(outboxRepo.findAllById(claimedIds)));
        List<OutboxMessage> messages = new ArrayList<>(rows.size());
        List<String> keys = new ArrayList<>(rows.size());
        for (OutboxEvent e : rows) {
            String key = e.getAggregateId().toString();
            keys.add(key);
            messages.add(new OutboxMessage(e.getTopic(), key, e.getPayload()));
        }
        publishLedger.beginPublish(claimedIds);
        BatchOutcome outcome;
        try {
            outcome = kafkaPublisher.publishBatch(messages, publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            releaseFailedClaims(claimedIds);
            publishLedger.settle(claimedIds);
            throw ex;
        }
        List<Boolean> delivered = new ArrayList<>(rows.size());
        List<String> errors = new ArrayList<>(rows.size());
        List<Long> deliveredIds = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            BatchOutcome.RecordOutcome record = (outcome != null && outcome.outcomes().size() == rows.size())
                    ? outcome.outcomes().get(i)
                    : null;
            boolean ok = record != null && record.success();
            delivered.add(ok);
            if (ok) {
                deliveredIds.add(rows.get(i).getId());
            }
            errors.add(record == null ? "publish did not complete before the deadline" : record.error());
        }
        publishLedger.confirmPublished(deliveredIds);
        log.debug("Outbox batch sent={} success={} completed={}",
                outcome == null ? 0 : outcome.sent(),
                outcome == null ? 0 : outcome.success(),
                outcome != null && outcome.completed());

        boolean[] markable = OutboxBatchOrdering.markableAsSent(keys, delivered);

        for (int i = 0; i < rows.size(); i++) {
            final OutboxEvent row = rows.get(i);
            final boolean sent = markable[i];
            final String error = errors.get(i);
            transactionTemplate.executeWithoutResult(tx -> markFinalState(row, sent, error));
        }
        publishLedger.settle(claimedIds);
    }

    private List<Long> claimPendingBatch() {
        List<Long> claimedIds = new ArrayList<>();
        for (int drained = 0; drained < batchSize; drained++) {
            Long claimed = transactionTemplate.execute(tx -> {
                Optional<OutboxEvent> locked = outboxRepo.claimOnePending(OutboxStatus.PENDING.name());
                if (locked.isEmpty()) {
                    return null;
                }
                OutboxEvent event = locked.get();
                event.setStatus(OutboxStatus.SENDING);
                event.setClaimedAt(Instant.now());
                event.setLastError(null);
                outboxRepo.save(event);
                return event.getId();
            });
            if (claimed == null) {
                break;
            }
            claimedIds.add(claimed);
        }
        return claimedIds;
    }

    private void releaseFailedClaims(List<Long> claimedIds) {
        Set<Long> published = publishLedger.publishedEventIds();
        List<Long> releasable = claimedIds.stream().filter(id -> !published.contains(id)).toList();
        if (releasable.isEmpty()) {
            return;
        }
        Integer released = transactionTemplate.execute(tx -> outboxRepo.releaseClaims(releasable,
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.DEAD, maxRetries,
                "the broker publish failed - the claim went back to the retry set"));
        if (released != null && released > 0) {
            log.warn("Released {} outbox claim(s) after the broker publish failed", released);
        }
    }

    @Scheduled(fixedDelayString = "${shop.shipping.outbox.recovery-poll-millis:30000}",
            initialDelayString = "${shop.shipping.outbox.recovery-initial-delay-millis:30000}")
    public void recoverStrandedClaims() {
        Instant cutoff = Instant.now().minusMillis(claimLeaseMs);
        Long exhausted = transactionTemplate.execute(tx -> outboxRepo.countStaleExhausted(
                OutboxStatus.SENDING, cutoff, maxRetries));
        if (exhausted != null && exhausted > 0) {
            metrics.recordOutboxFailed(exhausted.intValue());
            log.error("{} shipping outbox event(s) exhausted their retry budget and are parked as DEAD",
                    exhausted);
        }
        Integer reclaimed = transactionTemplate.execute(tx -> outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.DEAD,
                cutoff, maxRetries,
                "reclaimed after the claim lease expired - the relay died before publishing"));
        if (reclaimed != null && reclaimed > 0) {
            log.warn("Reclaimed {} outbox event(s) stranded in SENDING (claim lease {} ms expired)",
                    reclaimed, claimLeaseMs);
        }
    }

    private void markFinalState(OutboxEvent event, boolean published, String error) {
        OutboxEvent e = outboxRepo.findByIdForUpdate(event.getId()).orElse(null);
        if (e == null || e.getStatus() != OutboxStatus.SENDING) {
            return;
        }
        if (published) {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            e.setLastError(null);
        } else {
            e.setStatus(OutboxStatus.PENDING);
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError(truncate(error));
            if (e.getRetryCount() >= maxRetries) {
                e.setStatus(OutboxStatus.DEAD);
                metrics.recordOutboxFailed();
                log.error("Shipping outbox event {} permanently failed after {} attempt(s): {}",
                        e.getEventId(), e.getRetryCount(), e.getLastError());
            } else {
                log.warn("Shipping outbox event {} retry {}/{}: {}",
                        e.getEventId(), e.getRetryCount(), maxRetries, e.getLastError());
            }
        }
        outboxRepo.save(e);
    }

    static List<OutboxEvent> orderById(List<OutboxEvent> rows) {
        rows.sort(Comparator.comparing(OutboxEvent::getId));
        return rows;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown publish failure";
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
