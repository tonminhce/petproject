package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxStrandedClaimIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("shop.payment.outbox.poll-millis", () -> "3600000");
    }

    @Autowired
    OutboxEventRepository outboxRepo;

    @Autowired
    EntityManager entityManager;

    @BeforeEach
    void clearRows() {
        outboxRepo.deleteAll();
    }

    @Test
    @Transactional
    void strandedSendingRow_isReturnedToPendingSoItIsReclaimed() {
        OutboxEvent row = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(UUID.randomUUID())
                .eventType("payment.captured.v1")
                .topic("shop.payment.lifecycle.v1")
                .payload("{}")
                .status(OutboxStatus.SENDING)
                .retryCount(0)
                .build();
        outboxRepo.saveAndFlush(row);
        ageClaim(row, Instant.now().minusSeconds(3600));

        int reclaimed = outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                Instant.now().minusSeconds(60), 10, "lease expired");
        entityManager.clear();

        assertThat(reclaimed).isEqualTo(1);
        OutboxEvent recovered = outboxRepo.findById(row.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getRetryCount()).isEqualTo(1);
        assertThat(recovered.getLastError()).contains("lease expired");
    }

    @Test
    @Transactional
    void strandedRowWithNoRetryBudgetLeft_isParkedAsFailed() {
        OutboxEvent row = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(UUID.randomUUID())
                .eventType("payment.captured.v1")
                .topic("shop.payment.lifecycle.v1")
                .payload("{}")
                .status(OutboxStatus.SENDING)
                .retryCount(9)
                .build();
        outboxRepo.saveAndFlush(row);
        ageClaim(row, Instant.now().minusSeconds(3600));

        int reclaimed = outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                Instant.now().minusSeconds(60), 10, "lease expired");
        entityManager.clear();

        assertThat(reclaimed).isEqualTo(1);
        OutboxEvent parked = outboxRepo.findById(row.getId()).orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(parked.getRetryCount()).isEqualTo(10);
    }

    @Test
    @Transactional
    void aSendingRowWithoutAClaimTimestamp_isReclaimed() {
        OutboxEvent row = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(UUID.randomUUID())
                .eventType("payment.captured.v1")
                .topic("shop.payment.lifecycle.v1")
                .payload("{}")
                .status(OutboxStatus.SENDING)
                .retryCount(0)
                .build();
        outboxRepo.saveAndFlush(row);

        int reclaimed = outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                Instant.now().minusSeconds(60), 10, "lease expired");
        entityManager.clear();

        assertThat(reclaimed)
                .as("a row in SENDING without a lease timestamp must be re-armed, not stranded forever")
                .isEqualTo(1);
        assertThat(outboxRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @Transactional
    void aFreshSendingRow_isNotReclaimed() {
        OutboxEvent row = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(UUID.randomUUID())
                .eventType("payment.captured.v1")
                .topic("shop.payment.lifecycle.v1")
                .payload("{}")
                .status(OutboxStatus.SENDING)
                .retryCount(0)
                .build();
        outboxRepo.saveAndFlush(row);
        ageClaim(row, Instant.now());

        int reclaimed = outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                Instant.now().minusSeconds(60), 10, "lease expired");
        entityManager.clear();

        assertThat(reclaimed).isZero();
        assertThat(outboxRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENDING);
    }

    private void ageClaim(OutboxEvent event, Instant when) {
        event.setClaimedAt(when);
        outboxRepo.saveAndFlush(event);
    }
}
