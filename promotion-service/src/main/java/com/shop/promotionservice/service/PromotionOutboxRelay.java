package com.shop.promotionservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.kafka.producer.OutboxBatchOrdering;
import com.shop.common.kafka.producer.OutboxPublishLedger;
import com.shop.promotionservice.entity.OutboxEvent;
import com.shop.promotionservice.repository.OutboxEventRepository;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PromotionOutboxRelay {

    private static final List<String> CLAIMABLE_STATUSES = List.of(OutboxStatus.PENDING.name());
    private static final int AGGREGATE_WINDOW_FACTOR = 4;
    private static final long NO_EVENT_ID = -1L;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final PromotionMetrics metrics;

    private final TransactionTemplate transactionTemplate;
    private final OutboxPublishLedger publishLedger = new OutboxPublishLedger();
    private final String leaseOwner = ProcessHandle.current().pid() + "-" + UUID.randomUUID();

    @Value("${promotion.outbox.batch-size:100}")
    private int batchSize;

    @Value("${promotion.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${promotion.outbox.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    @Value("${promotion.outbox.claim-lease-ms:60000}")
    private long claimLeaseMs;

    public PromotionOutboxRelay(OutboxEventRepository outboxRepo,
                       KafkaMessagePublisher kafkaPublisher,
                       PromotionMetrics metrics,

                       PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.metrics = metrics;

        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${promotion.outbox.poll-interval-ms:5000}", initialDelayString = "${promotion.outbox.poll-interval-ms:5000}")
    public void relay() {
        Instant started = Instant.now();
        try {
            metrics.setPendingOutboxCount((int) outboxRepo.countByStatus(OutboxStatus.PENDING));

            List<OutboxEvent> rows = orderById(claimPendingBatch());
            if (rows.isEmpty()) {
                return;
            }
            List<Long> claimedIds = rows.stream().map(OutboxEvent::getId).toList();
            List<OutboxMessage> messages = new ArrayList<>(rows.size());
            List<String> keys = new ArrayList<>(rows.size());
            for (OutboxEvent e : rows) {
                String key = String.valueOf(e.getAggregateId());
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
        } finally {
            metrics.recordRelayDuration(Duration.between(started, Instant.now()));
        }
    }

    private List<OutboxEvent> claimPendingBatch() {
        List<UUID> aggregates = transactionTemplate.execute(tx ->
                outboxRepo.findClaimableAggregates(CLAIMABLE_STATUSES, aggregateWindow()));
        if (aggregates == null || aggregates.isEmpty()) {
            return new ArrayList<>();
        }
        List<OutboxEvent> claimedRows = new ArrayList<>();
        for (UUID aggregateId : distinctAggregates(aggregates)) {
            if (claimedRows.size() >= batchSize) {
                break;
            }
            List<OutboxEvent> rows = transactionTemplate.execute(tx ->
                    claimAggregate(aggregateId, batchSize - claimedRows.size()));
            if (rows != null) {
                claimedRows.addAll(rows);
            }
        }
        return claimedRows;
    }

    private List<OutboxEvent> claimAggregate(UUID aggregateId, int limit) {
        List<OutboxEvent> rows = outboxRepo.claimAggregateEvents(aggregateId, CLAIMABLE_STATUSES,
                OutboxStatus.SENDING.name(), limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        for (OutboxEvent event : rows) {
            event.setStatus(OutboxStatus.SENDING);
            event.setClaimedAt(now);
            event.setLeaseOwner(leaseOwner);
            event.setLastError(null);
        }
        outboxRepo.saveAll(rows);
        return rows;
    }

    private void releaseFailedClaims(List<Long> claimedIds) {
        Set<Long> published = publishLedger.publishedEventIds();
        List<Long> releasable = claimedIds.stream().filter(id -> !published.contains(id)).toList();
        if (releasable.isEmpty()) {
            return;
        }
        Integer released = transactionTemplate.execute(tx -> outboxRepo.releaseClaims(releasable,
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED, leaseOwner, maxRetries,
                "the broker publish failed - the claim went back to the retry set"));
        if (released != null && released > 0) {
            log.warn("Released {} outbox claim(s) after the broker publish failed", released);
        }
    }

    private int aggregateWindow() {
        return Math.max(batchSize * AGGREGATE_WINDOW_FACTOR, batchSize);
    }

    private static List<UUID> distinctAggregates(List<UUID> aggregates) {
        return new ArrayList<>(new LinkedHashSet<>(aggregates));
    }

    @PreDestroy
    void releaseOwnClaims() {
        Set<Long> publishedIds = publishLedger.publishedEventIds();
        Set<Long> inFlightIds = publishLedger.inFlightEventIds();
        Integer released = transactionTemplate.execute(tx -> {
            if (!publishedIds.isEmpty()) {
                outboxRepo.finalisePublishedClaims(new ArrayList<>(publishedIds), OutboxStatus.SENDING,
                        OutboxStatus.SENT, leaseOwner, Instant.now());
            }
            List<Long> excluded = new ArrayList<>(publishedIds);
            excluded.addAll(inFlightIds);
            if (excluded.isEmpty()) {
                excluded.add(NO_EVENT_ID);
            }
            return outboxRepo.releaseOwnedClaims(
                    OutboxStatus.SENDING, OutboxStatus.PENDING, leaseOwner, excluded);
        });
        if (released != null && released > 0) {
            log.warn("Released {} outbox event(s) still leased by this relay instance", released);
        }
    }

    @Scheduled(fixedDelayString = "${promotion.outbox.recovery-poll-millis:30000}",
            initialDelayString = "${promotion.outbox.recovery-initial-delay-millis:30000}")
    public void recoverStrandedClaims() {
        Instant cutoff = Instant.now().minusMillis(claimLeaseMs);
        Integer reclaimed = transactionTemplate.execute(tx -> outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
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
            e.setLeaseOwner(null);
            e.setLastError(null);
        } else {
            e.setStatus(OutboxStatus.PENDING);
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLeaseOwner(null);
            e.setClaimedAt(null);
            e.setLastError(truncate(error));
            if (e.getRetryCount() >= maxRetries) {
                e.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event {} permanently failed after {} attempt(s): {}",
                        e.getEventId(), e.getRetryCount(), e.getLastError());
            } else {
                log.warn("Outbox event {} retry {}/{}: {}",
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
