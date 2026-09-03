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
     * C14 fix — claim a single due row (PENDING or replayable FAILED — the
     * media relay polls both, H-5) with a row-level write lock so that
     * concurrent relay instances running in multiple pods can't pick the same
     * row and double-publish to Kafka. Postgres returns the row only if no
     * other transaction holds the lock (FOR UPDATE SKIP LOCKED); the doomed
     * "lost update" pattern that the previous design had on the
     * `status IN (PENDING, FAILED)` batch read is gone. Single-instance
     * deployments still work — only the locked-out instance sees an empty
     * result.
     *
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
     * H-5 relay aging: flips every FAILED row whose {@code failed_at} is past
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
