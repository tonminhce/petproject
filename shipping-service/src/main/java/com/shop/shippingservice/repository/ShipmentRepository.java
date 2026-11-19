package com.shop.shippingservice.repository;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findById(UUID id);

    Optional<Shipment> findByOrderId(UUID orderId);

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    Page<Shipment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Shipment> findAllByStatusOrderByCreatedAtDesc(ShipmentStatus status, Pageable pageable);

    Page<Shipment> findAllByCarrierOrderByCreatedAtDesc(Carrier carrier, Pageable pageable);

    /**
     * The shipments the stale sweep may auto-deliver: the carrier has
     * acknowledged them, and nothing has been heard from the carrier since
     * the cutoff. The window runs from the last carrier update when there
     * is one, otherwise from the acknowledgement - a shipment the carrier
     * accepted but never reported on must still be closed out, while a
     * shipment the carrier never accepted must never be.
     */
    @Query("SELECT s FROM Shipment s WHERE s.status IN :statuses "
         + "AND s.carrierAcknowledgedAt IS NOT NULL "
         + "AND COALESCE(s.lastCarrierUpdate, s.carrierAcknowledgedAt) < :cutoff")
    List<Shipment> findStaleAcknowledgedShipments(@Param("statuses") Collection<ShipmentStatus> statuses,
                                                   @Param("cutoff") Instant cutoff);

    boolean existsByOrderId(UUID orderId);
}
