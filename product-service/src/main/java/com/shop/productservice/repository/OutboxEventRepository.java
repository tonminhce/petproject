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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.id = ("
         + "SELECT MIN(e2.id) FROM OutboxEvent e2 WHERE e2.status = :status)")
    Optional<OutboxEvent> claimOnePending(@Param("status") OutboxStatus status);

    long countByStatus(OutboxStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEvent e WHERE e.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET "
         + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
         + "e.retryCount = e.retryCount + 1, "
         + "e.lastError = :reason "
         + "WHERE e.status = :sending AND e.updatedAt < :cutoff")
    int reclaimStaleSending(@Param("sending") OutboxStatus sending,
                             @Param("pending") OutboxStatus pending,
                             @Param("failed") OutboxStatus failed,
                             @Param("cutoff") Instant cutoff,
                             @Param("maxRetries") int maxRetries,
                             @Param("reason") String reason);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
