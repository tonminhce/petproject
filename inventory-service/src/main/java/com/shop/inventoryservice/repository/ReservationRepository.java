package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByProductIdAndStatusAndExpiresAtBefore(
            UUID productId, ReservationStatus status, Instant expiresBefore);

    // ReservationCleanupScheduler - sweep ALL pending-and-expired in pages.
    // Pageable is REQUIRED: an unbounded query can pull 50K+ entities after extended
    // downtime or a flash-sale backlog, blowing the persistence-context heap.
    // Hits idx_reservations_status_expires.
    List<Reservation> findByStatusAndExpiresAtBefore(
            ReservationStatus status, Instant expiresBefore, Pageable pageable);

    // ReservationCleanupScheduler.purgeOldExpiredReservations (Task 22) - retention EXPIRED
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Reservation r WHERE r.status = :status AND r.createdAt < :cutoff")
    int deleteByStatusAndCreatedAtBefore(@Param("status") ReservationStatus status,
                                         @Param("cutoff") Instant cutoff);

    long countByProductIdAndStatusIn(UUID productId, List<ReservationStatus> statuses);
}
