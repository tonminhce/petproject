package com.shop.mediaservice.outbox;

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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findByStatusInOrderByIdAsc(Collection<OutboxStatus> statuses, Pageable pageable);

    /**
     * Claim a single due row (PENDING or replayable FAILED — the
     * media relay polls both, H-5) with a row-level write lock so that
     * concurrent relay instances running in multiple pods can't pick the same
     * row and double-publish to Kafka. Postgres returns the row only if no
     * other transaction holds the lock (FOR UPDATE SKIP LOCKED); the doomed
     * "lost update" pattern that the previous design had on the
     * `status IN (PENDING, FAILED)` batch read is gone. Single-instance
     * deployments still work — only the locked-out instance sees an empty
     * result.
     * <p>The query hint {@code jakarta.persistence.lock.timeout = -2} is the
     * Hibernate/Spring portable form of SKIP LOCKED. The method returns
     * {@code Optional} so callers must check presence — an empty result here
     * is the "another pod grabbed it" signal.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.status IN :statuses AND e.id = ("
         + "SELECT MIN(e2.id) FROM OutboxEvent e2 WHERE e2.status IN :statuses)")
    Optional<OutboxEvent> claimOneDue(@Param("statuses") Collection<OutboxStatus> statuses);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);

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
     * two steps the row stays SENDING forever: it is neither PENDING nor
     * FAILED, so the due-claim never picks it up again, and nothing else in
     * the service touches it. The media event was silently lost.</p>
     *
     * <p>This statement returns a stale SENDING row to PENDING so it is
     * re-claimed, or parks it as FAILED (with the aging clock restarted)
     * when its retry budget is already spent - FAILED is not terminal here,
     * it is replayed until the retention window ages it to DEAD.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.failedAt = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :now ELSE e.failedAt END, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND e.claimedAt < :cutoff")
    int reclaimStaleSending(@Param("sending") OutboxStatus sending,
                             @Param("pending") OutboxStatus pending,
                             @Param("failed") OutboxStatus failed,
                             @Param("cutoff") Instant cutoff,
                             @Param("maxRetries") int maxRetries,
                             @Param("reason") String reason,
                             @Param("now") Instant now);

    /**
     * Relay aging: flips every FAILED row whose {@code failed_at} is past
     * the terminal window to DEAD (one bulk UPDATE — DEAD is terminal, the
     * relay never polls it again).
     *
     * @return number of rows aged to DEAD (drives the WARN + dead meter)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = com.shop.common.core.constants.OutboxStatus.DEAD "
            + "WHERE e.status = com.shop.common.core.constants.OutboxStatus.FAILED AND e.failedAt < :cutoff")
    int ageDeadFailedBefore(@Param("cutoff") Instant cutoff);
}
