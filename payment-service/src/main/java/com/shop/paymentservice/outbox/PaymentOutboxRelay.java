package com.shop.paymentservice.outbox;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.core.constants.OutboxStatus;
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
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PaymentOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.payment.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.payment.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${shop.payment.outbox.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    @Value("${shop.payment.outbox.claim-lease-ms:60000}")
    private long claimLeaseMs;

    private static final int MAX_ERROR_LENGTH = 1000;

    public PaymentOutboxRelay(OutboxEventRepository outboxRepo,
                              KafkaMessagePublisher kafkaPublisher,
                              PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.payment.outbox.poll-millis:2000}", initialDelayString = "${shop.payment.outbox.poll-millis:2000}")
    public void relay() {
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
        for (OutboxEvent e : rows) {
            messages.add(new OutboxMessage(e.getTopic(), e.getAggregateId().toString(), e.getPayload()));
        }
        BatchOutcome outcome = kafkaPublisher.publishBatch(
            messages, publishTimeoutMs, TimeUnit.MILLISECONDS);
        log.debug("Outbox batch sent={} success={} completed={}",
            outcome.sent(), outcome.success(), outcome.completed());

        List<BatchOutcome.RecordOutcome> outcomes = outcome.outcomes();
        for (int i = 0; i < rows.size(); i++) {
            BatchOutcome.RecordOutcome recordOutcome = (outcomes.size() == rows.size()) ? outcomes.get(i) : null;
            final boolean published = recordOutcome != null && recordOutcome.success();
            final String error = recordOutcome == null
                    ? "publish did not complete before the deadline"
                    : recordOutcome.error();
            final OutboxEvent row = rows.get(i);
            transactionTemplate.executeWithoutResult(tx -> markFinalState(row, published, error));
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

    private static String truncate(String message) {
        if (message == null) {
            return "unknown publish failure";
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    static List<OutboxEvent> orderById(List<OutboxEvent> rows) {
        rows.sort(Comparator.comparing(OutboxEvent::getId));
        return rows;
    }
}
