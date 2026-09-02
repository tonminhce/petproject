package com.shop.paymentservice.repository;

import com.shop.paymentservice.entity.PaymentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    /**
     * C3 — used by the dedup path to distinguish "already PROCESSED" (skip) from
     * "FAILED_RETRYABLE — let the scheduler pick it up again" (fall through).
     */
    Optional<PaymentEvent> findFirstByProviderAndProviderEventId(String provider, String providerEventId);

    /**
     * C3 — pull a batch of events whose retry window has elapsed, ordered oldest
     * first so a stuck event doesn't starve siblings.
     */
    List<PaymentEvent> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            String status, Instant cutoff, Pageable pageable);
}
