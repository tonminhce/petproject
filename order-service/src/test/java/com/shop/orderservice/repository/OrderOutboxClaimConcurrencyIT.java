package com.shop.orderservice.repository;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C14 proof on real Postgres (Testcontainers): two concurrent
 * {@code claimOnePending} attempts must never return the same PENDING row.
 * The FOR UPDATE SKIP LOCKED hint makes the second claimer skip the locked
 * head row and get an EMPTY claim — the "another pod grabbed it" signal — so
 * two relay instances can never double-publish the same outbox row. After the
 * first claimer commits, the still-PENDING row is claimable again (progress
 * resumes, exactly-once-per-claim-window preserved).
 */
class OrderOutboxClaimConcurrencyIT extends AbstractOrderServiceIT {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        // keep the @Scheduled relay idle so it cannot drain the rows under test
        registry.add("order.outbox.poll-interval-ms", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void clearPendingRows() {
        // leftover PENDING rows from other tests would win the MIN(id) claim race
        outboxRepo.deleteAll(outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 10_000)));
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .aggregateType("order")
            .aggregateId(UUID.randomUUID())
            .eventType("order.updated.v1")
            .topic("shop.order.events.v1")
            .payload("{}")
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .build();
    }

    @Test
    void concurrentClaim_lockedOutPeerGetsEmptyNeverTheSameRow() throws Exception {
        Long rowId = outboxRepo.save(pendingEvent()).getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch bLockedOut = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);
        AtomicReference<Optional<OutboxEvent>> claimA = new AtomicReference<>();
        AtomicReference<Optional<OutboxEvent>> claimBWhileLocked = new AtomicReference<>();
        AtomicReference<Optional<OutboxEvent>> claimBAfterCommit = new AtomicReference<>();

        // A claims the head row and HOLDS the row lock (tx open) while B claims concurrently.
        Thread claimerA = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    claimA.set(outboxRepo.claimOnePending(OutboxStatus.PENDING));
                    aClaimed.countDown();
                    try {
                        bLockedOut.await(10, TimeUnit.SECONDS); // hold the lock for B's attempt
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
                    claimBWhileLocked.set(tx.execute(s -> outboxRepo.claimOnePending(OutboxStatus.PENDING)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                bLockedOut.countDown();
            }
            try {
                if (aCommitted.await(10, TimeUnit.SECONDS)) {
                    claimBAfterCommit.set(tx.execute(s -> outboxRepo.claimOnePending(OutboxStatus.PENDING)));
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

        // A claimed the head row
        assertThat(claimA.get()).isPresent();
        assertThat(claimA.get().map(OutboxEvent::getId)).contains(rowId);
        // While A's lock was held, B got an EMPTY claim — never the same row (SKIP LOCKED)
        assertThat(claimBWhileLocked.get()).isEmpty();
        // After A committed, the still-PENDING row is claimable again — progress resumes
        assertThat(claimBAfterCommit.get().map(OutboxEvent::getId)).contains(rowId);

        outboxRepo.deleteById(rowId);
    }

    @Test
    void twoPendingRows_secondRowUntouchedWhileHeadRowLocked() throws Exception {
        Long row1 = outboxRepo.save(pendingEvent()).getId();
        Long row2 = outboxRepo.save(pendingEvent()).getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch bLockedOut = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);
        AtomicReference<Optional<OutboxEvent>> claimA = new AtomicReference<>();
        AtomicReference<Optional<OutboxEvent>> claimBWhileLocked = new AtomicReference<>();
        AtomicReference<Optional<OutboxEvent>> claimBAfterCommit = new AtomicReference<>();

        Thread claimerA = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    claimA.set(outboxRepo.claimOnePending(OutboxStatus.PENDING));
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
                    claimBWhileLocked.set(tx.execute(s -> outboxRepo.claimOnePending(OutboxStatus.PENDING)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                bLockedOut.countDown();
            }
            try {
                if (aCommitted.await(10, TimeUnit.SECONDS)) {
                    claimBAfterCommit.set(tx.execute(s -> outboxRepo.claimOnePending(OutboxStatus.PENDING)));
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

        // A claimed the head row (MIN id)
        assertThat(claimA.get().map(OutboxEvent::getId)).contains(row1);
        // B locked out entirely — even though row2 is free, the MIN-subquery claim
        // shape serializes on the head row (see claimOnePending javadoc)
        assertThat(claimBWhileLocked.get()).isEmpty();
        // After A committed, row1 is re-claimable; row2 was never touched
        assertThat(claimBAfterCommit.get().map(OutboxEvent::getId)).contains(row1);
        assertThat(outboxRepo.findById(row2).map(OutboxEvent::getStatus)).contains(OutboxStatus.PENDING);

        outboxRepo.deleteById(row1);
        outboxRepo.deleteById(row2);
    }
}
