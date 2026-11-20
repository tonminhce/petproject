package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxClaimConcurrencyIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("shop.payment.outbox.poll-millis", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void clearPendingRows() {
        outboxRepo.deleteAll(outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 10_000)));
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .aggregateType("payment")
            .aggregateId(UUID.randomUUID())
            .eventType("payment.updated.v1")
            .topic("shop.payment.events.v1")
            .payload("{}")
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .build();
    }

    private List<String> claimableStatuses() {
        return List.of(OutboxStatus.PENDING.name());
    }

    @Test
    void concurrentClaim_lockedOutPeerGetsEmptyNeverTheSameRow() throws Exception {
        OutboxEvent row = outboxRepo.saveAndFlush(pendingEvent());
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch bLockedOut = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);
        AtomicReference<List<OutboxEvent>> claimA = new AtomicReference<>();
        AtomicReference<List<OutboxEvent>> claimBWhileLocked = new AtomicReference<>();
        AtomicReference<List<OutboxEvent>> claimBAfterCommit = new AtomicReference<>();

        Thread claimerA = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    claimA.set(outboxRepo.claimAggregateEvents(row.getAggregateId(), claimableStatuses(),
                            OutboxStatus.SENDING.name(), 1));
                    aClaimed.countDown();
                    try {
                        bLockedOut.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                aCommitted.countDown();
            }
        });
        Thread claimerB = new Thread(() -> {
            try {
                if (aClaimed.await(10, TimeUnit.SECONDS)) {
                    claimBWhileLocked.set(tx.execute(s -> outboxRepo.claimAggregateEvents(
                            row.getAggregateId(), claimableStatuses(), OutboxStatus.SENDING.name(), 1)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                bLockedOut.countDown();
            }
            try {
                if (aCommitted.await(10, TimeUnit.SECONDS)) {
                    claimBAfterCommit.set(tx.execute(s -> outboxRepo.claimAggregateEvents(
                            row.getAggregateId(), claimableStatuses(), OutboxStatus.SENDING.name(), 1)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        claimerA.start();
        claimerB.start();
        claimerA.join(20_000);
        claimerB.join(20_000);

        assertThat(claimerA.isAlive()).isFalse();
        assertThat(claimerB.isAlive()).isFalse();

        assertThat(claimA.get()).hasSize(1);
        assertThat(claimA.get()).extracting(OutboxEvent::getId).containsExactly(row.getId());
        assertThat(claimBWhileLocked.get())
                .as("a locked aggregate must never be claimed by a second instance")
                .isEmpty();
        assertThat(claimBAfterCommit.get()).extracting(OutboxEvent::getId).containsExactly(row.getId());

        outboxRepo.deleteById(row.getId());
    }

    @Test
    void twoPendingRows_secondAggregateClaimedConcurrentlyWhileFirstAggregateLocked() throws Exception {
        OutboxEvent first = outboxRepo.saveAndFlush(pendingEvent());
        OutboxEvent second = outboxRepo.saveAndFlush(pendingEvent());
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch bLockedOut = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);
        AtomicReference<List<OutboxEvent>> claimBWhileLocked = new AtomicReference<>();
        AtomicReference<List<OutboxEvent>> claimBAfterCommit = new AtomicReference<>();

        Thread claimerA = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    outboxRepo.claimAggregateEvents(first.getAggregateId(), claimableStatuses(),
                            OutboxStatus.SENDING.name(), 1);
                    aClaimed.countDown();
                    try {
                        bLockedOut.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                aCommitted.countDown();
            }
        });
        Thread claimerB = new Thread(() -> {
            try {
                if (aClaimed.await(10, TimeUnit.SECONDS)) {
                    claimBWhileLocked.set(tx.execute(s -> outboxRepo.claimAggregateEvents(
                            second.getAggregateId(), claimableStatuses(), OutboxStatus.SENDING.name(), 1)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                bLockedOut.countDown();
            }
            try {
                if (aCommitted.await(10, TimeUnit.SECONDS)) {
                    claimBAfterCommit.set(tx.execute(s -> outboxRepo.claimAggregateEvents(
                            first.getAggregateId(), claimableStatuses(), OutboxStatus.SENDING.name(), 1)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        claimerA.start();
        claimerB.start();
        claimerA.join(20_000);
        claimerB.join(20_000);

        assertThat(claimerA.isAlive()).isFalse();
        assertThat(claimerB.isAlive()).isFalse();

        assertThat(claimBWhileLocked.get())
                .as("a locked aggregate must not block the claim of another aggregate")
                .extracting(OutboxEvent::getId)
                .containsExactly(second.getId());
        assertThat(claimBAfterCommit.get())
                .extracting(OutboxEvent::getId)
                .containsExactly(first.getId());

        outboxRepo.deleteById(first.getId());
        outboxRepo.deleteById(second.getId());
    }

    @Test
    void anAggregateWithAnEventInFlight_isNotClaimedAgain() {
        OutboxEvent inFlight = outboxRepo.saveAndFlush(pendingEvent());
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            List<OutboxEvent> claimed = outboxRepo.claimAggregateEvents(inFlight.getAggregateId(),
                    claimableStatuses(), OutboxStatus.SENDING.name(), 1);
            assertThat(claimed).hasSize(1);
            claimed.get(0).setStatus(OutboxStatus.SENDING);
            claimed.get(0).setClaimedAt(Instant.now());
            outboxRepo.saveAll(claimed);
        });

        List<OutboxEvent> second = tx.execute(t -> outboxRepo.claimAggregateEvents(inFlight.getAggregateId(),
                claimableStatuses(), OutboxStatus.SENDING.name(), 1));

        assertThat(second)
                .as("an aggregate with an event in flight must not be claimed again")
                .isEmpty();
    }
}
