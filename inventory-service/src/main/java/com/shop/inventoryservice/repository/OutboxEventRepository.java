package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.OutboxEvent;
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

    // Poller - ordered by id ASC keeps per-aggregate event ordering intact.
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
     * Re-reads a row under its write lock for the mark phase.
     *
     * <p>The mark phase must be a compare-and-set: read the row, check it is
     * still SENDING, then write the new status. Without the lock the stale
     * sweep could move the row from SENDING to PENDING between the read and
     * the write, and the mark would then flip a row that another relay
     * instance may already have re-claimed - a double publish that the row
     * state said was impossible.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);

    /**
     * Recovers events stranded in the SENDING state.
     *
     * <p>The relay claims a row by moving it to SENDING and committing, then
     * publishes it outside that transaction. If the process dies between the
     * two steps the row stays SENDING forever: it is no longer PENDING, so
     * the claim never picks it up again, and nothing else in the service
     * touches it. A stock event would be silently lost - the reservation
     * moved but the event that tells the rest of the fleet never arrived.</p>
     *
     * <p>This statement returns a stale SENDING row to PENDING so it is
     * re-claimed, unless it has already burned its retry budget, in which
     * case it is parked as FAILED for an operator. {@code claimedAt} is the
     * claim timestamp, so the cutoff is the claim lease.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND e.claimedAt < :cutoff")
    int reclaimStaleSending(@Param("sending") OutboxStatus sending,
                             @Param("pending") OutboxStatus pending,
                             @Param("failed") OutboxStatus failed,
                             @Param("cutoff") Instant cutoff,
                             @Param("maxRetries") int maxRetries,
                             @Param("reason") String reason);

    // OutboxRetentionScheduler - bulk delete without loading the entities.
    // so subsequent operations on the same persistence unit do not touch detached
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
