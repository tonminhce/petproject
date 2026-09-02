package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
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
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.id = ("
         + "SELECT MIN(e2.id) FROM OutboxEvent e2 WHERE e2.status = :status)")
    Optional<OutboxEvent> claimOnePending(@Param("status") OutboxStatus status);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);

    /** H11 — purge DEAD rows older than the aging window. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.updatedAt < :cutoff")
    int deleteByStatusAndUpdatedAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);
}
