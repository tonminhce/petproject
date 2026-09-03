package com.shop.promotionservice.repository;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.promotionservice.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Poller - ordered by id ASC keeps per-aggregate event ordering intact.
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    /**
     * C14 fix — claim a single PENDING row with a row-level write lock so that
     * concurrent relay instances running in multiple pods can't pick the same
     * row and double-publish to Kafka. Postgres returns the row only if no
     * other transaction holds the lock (FOR UPDATE SKIP LOCKED); the doomed
     * "lost update" pattern that the previous design had on `status = PENDING`
     * is gone. Single-instance deployments still work — only the locked-out
     * instance sees an empty result.
     *
     * <p>The query hint {@code jakarta.persistence.lock.timeout = -2} is the
     * Hibernate/Spring portable form of SKIP LOCKED. The method returns
     * {@code Optional} so callers must check presence — an empty list here is
     * the "another pod grabbed it" signal.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    Optional<OutboxEvent> findFirstByStatusOrderByIdAsc(OutboxStatus status);

    default Optional<OutboxEvent> claimOnePending(OutboxStatus status) {
        return findFirstByStatusOrderByIdAsc(status);
    }

    // PromotionOutboxRelay pending gauge — true backlog, not batch-capped.
    long countByStatus(OutboxStatus status);

    // OutboxRetentionScheduler - bulk delete without loading the entities.
    // clearAutomatically drops stale persistence-context refs after the bulk delete
    // so subsequent operations on the same persistence unit do not touch detached
    // proxies (defensive hardening - the retention scheduler is its own consumer).
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
