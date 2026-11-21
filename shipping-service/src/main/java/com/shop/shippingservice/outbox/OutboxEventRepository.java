package com.shop.shippingservice.outbox;

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

    @Query(value = "SELECT * FROM outbox_events WHERE status = :status "
                + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OutboxEvent> claimOnePending(@Param("status") String status);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.claimedAt < :cutoff")
    int deleteByStatusAndClaimedAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :dead ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.claimedAt = null, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND e.id IN :ids")
    int releaseClaims(@Param("ids") List<Long> ids,
                      @Param("sending") OutboxStatus sending,
                      @Param("pending") OutboxStatus pending,
                      @Param("dead") OutboxStatus dead,
                      @Param("maxRetries") int maxRetries,
                      @Param("reason") String reason);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND (e.claimedAt IS NULL OR e.claimedAt < :cutoff)")
    int reclaimStaleSending(@Param("sending") OutboxStatus sending,
                             @Param("pending") OutboxStatus pending,
                             @Param("failed") OutboxStatus failed,
                             @Param("cutoff") Instant cutoff,
                             @Param("maxRetries") int maxRetries,
                             @Param("reason") String reason);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status = :sending AND e.claimedAt < :cutoff "
         + "AND e.retryCount + 1 >= :maxRetries")
    long countStaleExhausted(@Param("sending") OutboxStatus sending,
                             @Param("cutoff") Instant cutoff,
                             @Param("maxRetries") int maxRetries);
}
