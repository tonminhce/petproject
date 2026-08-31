package com.shop.shippingservice.repository;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findById(UUID id);

    Optional<Shipment> findByOrderId(UUID orderId);

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    Page<Shipment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Shipment> findAllByStatusOrderByCreatedAtDesc(ShipmentStatus status, Pageable pageable);

    Page<Shipment> findAllByCarrierOrderByCreatedAtDesc(Carrier carrier, Pageable pageable);

    List<Shipment> findByStatusInAndLastCarrierUpdateBefore(Collection<ShipmentStatus> statuses, Instant cutoff);

    boolean existsByOrderId(UUID orderId);
}
