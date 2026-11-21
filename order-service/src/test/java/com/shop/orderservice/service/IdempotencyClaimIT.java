package com.shop.orderservice.service;

import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyClaimIT extends AbstractOrderServiceIT {

    @Autowired
    IdempotencyKeyRepository repository;

    @Autowired
    IdempotencyService idempotencyService;

    @Test
    @Transactional
    void aLostClaimMustNotRewriteTheRecordOwnedByTheWinningTransaction() {
        String actor = "actor-lost-claim";
        String key = "key-lost-claim";
        String hash = "hash-lost-claim";
        Instant expiry = Instant.now().plusSeconds(3600);

        IdempotencyKey completed = new IdempotencyKey(actor, key, hash, 200, "{\"cached\":true}",
                Instant.now(), expiry);
        repository.saveAndFlush(completed);

        int claimed = repository.insertIfAbsent(actor, key, hash, expiry);

        assertThat(claimed).isZero();
        IdempotencyKey stored = repository.findByActorAndKey(actor, key).orElseThrow();
        assertThat(stored.getResponseStatus()).isEqualTo(200);
        assertThat(stored.getResponseBody()).isEqualTo("{\"cached\":true}");
    }

    @Test
    void concurrentClaimsOnTheSameKeyMustProduceExactlyOneOwner() throws Exception {
        int iterations = 25;
        for (int i = 0; i < iterations; i++) {
            String actor = "actor-race-" + i;
            String key = "key-race-" + i;
            String hash = "hash-race-" + i;
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger owners = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            Runnable task = () -> {
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                    if (idempotencyService.begin(key, actor, hash).isEmpty()) {
                        owners.incrementAndGet();
                    }
                } catch (com.shop.common.core.exception.BusinessException expected) {
                    assertThat(expected.getErrorCode())
                        .as("the caller that lost the race must be rejected as a duplicate request")
                        .isEqualTo(com.shop.common.core.exception.ErrorCode.ORDER_DUPLICATE_REQUEST.getCode());
                    rejected.incrementAndGet();
                } catch (Throwable failure) {
                    throw new AssertionError(
                        "the race must only be decided by the duplicate request rule, not by a failure of the "
                            + "database or of the code under test",
                        failure
                    );
                }
            };
            Thread first = new Thread(task, "idempotency-race-a-" + i);
            Thread second = new Thread(task, "idempotency-race-b-" + i);
            first.start();
            second.start();
            first.join(30000);
            second.join(30000);

            assertThat(owners.get())
                .as("callers that own the idempotency key " + key)
                .isEqualTo(1);
            assertThat(owners.get() + rejected.get())
                .as("callers that reached a decision for the idempotency key " + key)
                .isEqualTo(2);
        }
    }
}