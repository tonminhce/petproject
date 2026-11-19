package com.shop.shippingservice.repository;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.entity.ShipmentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentEventRepository extends JpaRepository<ShipmentEvent, UUID> {

    boolean existsByCarrierAndProviderEventId(Carrier carrier, String eventId);

    Optional<ShipmentEvent> findFirstByCarrierAndProviderEventId(Carrier carrier, String providerEventId);

    @Query("SELECT e FROM ShipmentEvent e WHERE e.status = :status "
         + "AND e.nextRetryAt <= :cutoff "
         + "AND (e.claimedAt IS NULL OR e.claimedAt < :staleBefore) "
         + "ORDER BY e.nextRetryAt ASC")
    List<ShipmentEvent> findClaimableByStatus(@Param("status") String status,
                                              @Param("cutoff") Instant cutoff,
                                              @Param("staleBefore") Instant staleBefore,
                                              Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ShipmentEvent e SET e.claimedAt = :claimedAt "
         + "WHERE e.id = :id AND e.status = :status "
         + "AND (e.claimedAt IS NULL OR e.claimedAt < :staleBefore)")
    int claimForRetry(@Param("id") UUID id,
                      @Param("status") String status,
                      @Param("claimedAt") Instant claimedAt,
                      @Param("staleBefore") Instant staleBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ShipmentEvent e SET e.claimedAt = NULL WHERE e.id = :id")
    int releaseClaim(@Param("id") UUID id);
}
