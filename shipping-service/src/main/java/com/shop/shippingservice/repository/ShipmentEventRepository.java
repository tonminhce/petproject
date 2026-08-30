package com.shop.shippingservice.repository;

import com.shop.shippingservice.entity.ShipmentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShipmentEventRepository extends JpaRepository<ShipmentEvent, UUID> {

    boolean existsByCarrierAndProviderEventId(String carrier, String eventId);
}
