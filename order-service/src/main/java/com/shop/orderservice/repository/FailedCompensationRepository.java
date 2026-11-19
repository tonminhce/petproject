package com.shop.orderservice.repository;

import com.shop.orderservice.entity.FailedCompensation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FailedCompensationRepository extends JpaRepository<FailedCompensation, UUID> {

    boolean existsByReservationIdAndKindAndStatusIn(
            UUID reservationId, String kind, Collection<String> statuses);

    @Query("SELECT f FROM FailedCompensation f "
         + "WHERE f.status IN :statuses AND (f.nextAttemptAt IS NULL OR f.nextAttemptAt <= :now) "
         + "ORDER BY f.createdAt ASC")
    List<FailedCompensation> findDue(@Param("statuses") Collection<String> statuses,
                                     @Param("now") Instant now,
                                     Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FailedCompensation f SET f.status = :processing, f.nextAttemptAt = :leaseUntil, f.updatedAt = :now "
         + "WHERE f.id = :id AND f.status IN :statuses "
         + "AND (f.nextAttemptAt IS NULL OR f.nextAttemptAt <= :now)")
    int claimForProcessing(@Param("id") UUID id,
                           @Param("processing") String processing,
                           @Param("statuses") Collection<String> statuses,
                           @Param("leaseUntil") Instant leaseUntil,
                           @Param("now") Instant now);
}
