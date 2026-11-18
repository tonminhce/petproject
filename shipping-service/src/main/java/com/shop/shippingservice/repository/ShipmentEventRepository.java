package com.shop.shippingservice.repository;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.entity.ShipmentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentEventRepository extends JpaRepository<ShipmentEvent, UUID> {

    boolean existsByCarrierAndProviderEventId(Carrier carrier, String eventId);

    Optional<ShipmentEvent> findFirstByCarrierAndProviderEventId(Carrier carrier, String providerEventId);

    List<ShipmentEvent> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            String status, Instant cutoff, Pageable pageable);
}
