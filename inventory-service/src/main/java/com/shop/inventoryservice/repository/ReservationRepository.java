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

    // Pageable is REQUIRED: an unbounded query can pull 50K+ entities after extended
    List<Reservation> findByStatusAndExpiresAtBefore(
            ReservationStatus status, Instant expiresBefore, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Reservation r WHERE r.status = :status AND r.createdAt < :cutoff")
    int deleteByStatusAndCreatedAtBefore(@Param("status") ReservationStatus status,
                                         @Param("cutoff") Instant cutoff);

    long countByProductIdAndStatusIn(UUID productId, List<ReservationStatus> statuses);

    /**
     * Claim a reservation for TTL expiry.
     * <p>The expiry sweep used to SELECT the candidates and then blind-save them
     * as EXPIRED. Because the reservations table had no version column, a sweep
     * that read the row before {@code commit()} ran could overwrite the COMMITTED
     * state with EXPIRED - releasing stock that a paid order had already
     * consumed.</p>
     * <p>This conditional UPDATE is the claim: only the writer whose UPDATE
     * matches {@code status = PENDING} transitions the row, and every other
     * writer (a concurrent commit, a competing sweep) affects zero rows and
     * therefore does NOT touch the inventory counters.</p>
     *
     * @return 1 when this caller claimed the row, 0 when someone else already
     *         transitioned it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = :expired, r.version = r.version + 1 "
         + "WHERE r.id = :id AND r.status = :pending")
    int claimForExpiry(@Param("id") UUID id,
                        @Param("pending") ReservationStatus pending,
                        @Param("expired") ReservationStatus expired);
}
