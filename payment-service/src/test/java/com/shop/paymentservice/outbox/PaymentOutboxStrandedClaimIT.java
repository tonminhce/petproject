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

/**
 * F-052 (P1) — an event stranded in the SENDING state must be recoverable.
 *
 * <p>The relay claims a row by moving it to SENDING and committing, then
 * publishes it outside that transaction. If the process dies between the two
 * steps the row stays SENDING forever: it is no longer PENDING, so the claim
 * never picks it up again, and nothing else in the service touches it. The
 * event was silently lost with no alarm.</p>
 */
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
        ageClaim(row.getId(), Instant.now().minusSeconds(3600));

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
        ageClaim(row.getId(), Instant.now().minusSeconds(3600));

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

        int reclaimed = outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                Instant.now().minusSeconds(60), 10, "lease expired");
        entityManager.clear();

        // The publish is still in flight - the recovery must not touch it.
        assertThat(reclaimed).isZero();
        assertThat(outboxRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENDING);
    }

    /** Age the claim past the lease; {@code @UpdateTimestamp} would overwrite it on save. */
    private void ageClaim(Long id, Instant when) {
        entityManager.createNativeQuery("UPDATE outbox_events SET updated_at = ?1 WHERE id = ?2")
                .setParameter(1, when)
                .setParameter(2, id)
                .executeUpdate();
        entityManager.clear();
    }
}
