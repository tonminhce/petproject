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

    // Poller - sap xep theo id ASC giu thu tu per-aggregate
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    // OutboxRetentionScheduler (Task 22) - bulk delete, khong load entity.
    // clearAutomatically: don persistence context sau bulk delete de stale entity refs
    // khong ton tai trong cac thao tac tiep theo (hardening - hien scheduler dung rieng).
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status,
                                      @Param("cutoff") Instant cutoff);
}
