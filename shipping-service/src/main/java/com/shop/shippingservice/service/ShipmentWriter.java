package com.shop.shippingservice.service;

import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ShipmentWriter {

    private final ShipmentRepository repository;

    @Transactional
    public Shipment insert(Shipment shipment) {
        return repository.saveAndFlush(shipment);
    }

    @Transactional
    public Shipment save(Shipment shipment) {
        return repository.save(shipment);
    }
}
