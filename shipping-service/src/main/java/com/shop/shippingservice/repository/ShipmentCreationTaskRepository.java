package com.shop.shippingservice.repository;

import com.shop.shippingservice.constant.ShipmentCreationStatus;
import com.shop.shippingservice.entity.ShipmentCreationTask;
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
import java.util.UUID;

public interface ShipmentCreationTaskRepository extends JpaRepository<ShipmentCreationTask, UUID> {

    Optional<ShipmentCreationTask> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT t FROM ShipmentCreationTask t WHERE t.status = :status "
         + "AND t.nextAttemptAt <= :now "
         + "AND (t.claimedAt IS NULL OR t.claimedAt < :staleBefore) "
         + "ORDER BY t.nextAttemptAt ASC")
    List<ShipmentCreationTask> findClaimable(@Param("status") ShipmentCreationStatus status,
                                              @Param("now") Instant now,
                                              @Param("staleBefore") Instant staleBefore,
                                              Pageable pageable);
}
