package com.shop.promotionservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.promotionservice.entity.OutboxEvent;
import com.shop.promotionservice.repository.OutboxEventRepository;
import com.shop.promotionservice.support.AbstractIntegrationTest;
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

class PromotionOutboxClaimConcurrencyIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("promotion.outbox.poll-interval-ms", () -> "3600000");
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
            .aggregateType("promotion")
            .aggregateId(UUID.randomUUID())
            .eventType("promotion.updated.v1")
            .topic("shop.promotion.events.v1")
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

        assertThat(claimA.get()).isPresent();
        assertThat(claimA.get().map(OutboxEvent::getId)).contains(rowId);
        assertThat(claimBWhileLocked.get()).isEmpty();
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

        assertThat(claimA.get().map(OutboxEvent::getId)).contains(row1);
        assertThat(claimBWhileLocked.get()).isEmpty();
        assertThat(claimBAfterCommit.get().map(OutboxEvent::getId)).contains(row1);
        assertThat(outboxRepo.findById(row2).map(OutboxEvent::getStatus)).contains(OutboxStatus.PENDING);

        outboxRepo.deleteById(row1);
        outboxRepo.deleteById(row2);
    }
}
