package com.shop.ratingservice.outbox;

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
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    @Query(value = "SELECT aggregate_id FROM outbox_events WHERE status IN (:statuses) "
            + "ORDER BY id LIMIT :limit", nativeQuery = true)
    List<UUID> findClaimableAggregates(@Param("statuses") Collection<String> statuses, @Param("limit") int limit);

    @Query(value = "SELECT e.* FROM outbox_events e "
            + "WHERE e.aggregate_id = :aggregateId "
            + "AND e.status IN (:statuses) "
            + "AND pg_try_advisory_xact_lock(hashtextextended(e.aggregate_id::text, 0)) "
            + "AND NOT EXISTS (SELECT 1 FROM outbox_events s "
            + "WHERE s.aggregate_id = e.aggregate_id AND s.status = :sending) "
            + "ORDER BY e.id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> claimAggregateEvents(@Param("aggregateId") UUID aggregateId,
                                            @Param("statuses") Collection<String> statuses,
                                            @Param("sending") String sending,
                                            @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = :pending, e.leaseOwner = null, e.claimedAt = null "
         + "WHERE e.status = :sending AND e.leaseOwner = :owner AND e.id NOT IN :excludedIds")
    int releaseOwnedClaims(@Param("sending") OutboxStatus sending,
                           @Param("pending") OutboxStatus pending,
                           @Param("owner") String owner,
                           @Param("excludedIds") List<Long> excludedIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = :sent, e.sentAt = :sentAt, "
         + "e.leaseOwner = null, e.claimedAt = null, e.lastError = null "
         + "WHERE e.status = :sending AND e.leaseOwner = :owner AND e.id IN :ids")
    int finalisePublishedClaims(@Param("ids") List<Long> ids,
                                @Param("sending") OutboxStatus sending,
                                @Param("sent") OutboxStatus sent,
                                @Param("owner") String owner,
                                @Param("sentAt") Instant sentAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.claimedAt = null, "
         + "e.leaseOwner = null, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND e.leaseOwner = :owner AND e.id IN :ids")
    int releaseClaims(@Param("ids") List<Long> ids,
                      @Param("sending") OutboxStatus sending,
                      @Param("pending") OutboxStatus pending,
                      @Param("failed") OutboxStatus failed,
                      @Param("owner") String owner,
                      @Param("maxRetries") int maxRetries,
                      @Param("reason") String reason);

    long countByStatus(OutboxStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.claimedAt = null, "
         + "e.leaseOwner = null, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND (e.claimedAt IS NULL OR e.claimedAt < :cutoff)")
    int reclaimStaleSending(@Param("sending") OutboxStatus sending,
                             @Param("pending") OutboxStatus pending,
                             @Param("failed") OutboxStatus failed,
                             @Param("cutoff") Instant cutoff,
                             @Param("maxRetries") int maxRetries,
                             @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
