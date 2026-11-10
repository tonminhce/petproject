package com.shop.productservice.repository;

import com.shop.productservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    /**
     * Claim a single PENDING row with a row-level write lock so that
     * concurrent relay instances running in multiple pods can't pick the same
     * row and double-publish to Kafka. Postgres returns the row only if no
     * other transaction holds the lock (FOR UPDATE SKIP LOCKED); the doomed
     * "lost update" pattern that the previous design had on `status = PENDING`
     * is gone. Single-instance deployments still work — only the locked-out
     * instance sees an empty result.
     * <p>The query hint {@code jakarta.persistence.lock.timeout = -2} is the
     * Hibernate/Spring portable form of SKIP LOCKED. The method returns
     * {@code Optional} so callers must check presence — an empty list here is
     * the "another pod grabbed it" signal.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.id = ("
         + "SELECT MIN(e2.id) FROM OutboxEvent e2 WHERE e2.status = :status)")
    Optional<OutboxEvent> claimOnePending(@Param("status") OutboxStatus status);

    long countByStatus(OutboxStatus status);

    /**
     * <p>product-service had NO retention scheduler, so its {@code outbox_events}
     * table grew by one row per product/brand/category mutation forever, with
     * nothing ever reclaiming the space. Every other event-producing service
     * (inventory, promotion, media, payment, shipping, order) purges SENT rows
     * older than a retention window; this service was the outlier and its
     * repository did not even have the delete method.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
