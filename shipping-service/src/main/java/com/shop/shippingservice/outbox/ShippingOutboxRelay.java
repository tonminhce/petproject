package com.shop.shippingservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.kafka.producer.OutboxBatchOrdering;
import com.shop.shippingservice.outbox.OutboxEvent;
import com.shop.shippingservice.outbox.OutboxEventRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ShippingOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;

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
                       PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(
            fixedDelayString = "${shop.shipping.outbox.poll-millis:2000}",
            initialDelayString = "${shop.shipping.outbox.poll-millis:2000}")
    public void relay() {
        Instant started = Instant.now();
        try {
            recoverStrandedClaims();

            List<Long> claimedIds = new ArrayList<>();
            for (int drained = 0; drained < batchSize; drained++) {
                Long claimed = transactionTemplate.execute(tx -> {
                    Optional<OutboxEvent> locked = outboxRepo.claimOnePending(OutboxStatus.PENDING);
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
            BatchOutcome outcome = kafkaPublisher.publishBatch(
                    messages, publishTimeoutMs, TimeUnit.MILLISECONDS);
            List<Boolean> delivered = new ArrayList<>(rows.size());
            List<String> errors = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                BatchOutcome.RecordOutcome record = (outcome != null && outcome.outcomes().size() == rows.size())
                        ? outcome.outcomes().get(i)
                        : null;
                delivered.add(record != null && record.success());
                errors.add(record == null ? "publish did not complete before the deadline" : record.error());
            }
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
        } finally {
        }
    }

    private void recoverStrandedClaims() {
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
            e.setLastError(null);
        } else {
            e.setStatus(OutboxStatus.PENDING);
            e.setRetryCount(e.getRetryCount() + 1);
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
