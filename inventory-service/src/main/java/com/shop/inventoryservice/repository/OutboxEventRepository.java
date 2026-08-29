package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Poller - ordered by id ASC keeps per-aggregate event ordering intact.
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    // OutboxRetentionScheduler - bulk delete without loading the entities.
    // clearAutomatically drops stale persistence-context refs after the bulk delete
    // so subsequent operations on the same persistence unit do not touch detached
    // proxies (defensive hardening - the retention scheduler is its own consumer).
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
