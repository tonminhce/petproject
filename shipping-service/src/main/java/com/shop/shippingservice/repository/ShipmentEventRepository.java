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

    /**
     * C3 — same shape as payment-service's repository: used by dedup to
     * distinguish PROCESSED-skip from FAILED_RETRYABLE-fall-through.
     */
    Optional<ShipmentEvent> findFirstByCarrierAndProviderEventId(Carrier carrier, String providerEventId);

    /**
     * C3 — batch pull of events whose retry window has elapsed.
     */
    List<ShipmentEvent> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            String status, Instant cutoff, Pageable pageable);
}
