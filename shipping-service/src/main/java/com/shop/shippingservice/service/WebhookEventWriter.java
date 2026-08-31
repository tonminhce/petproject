package com.shop.shippingservice.service;

import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentEvent;
import com.shop.shippingservice.outbox.ShippingEventPublisher;
import com.shop.shippingservice.repository.ShipmentEventRepository;
import com.shop.shippingservice.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class WebhookEventWriter {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventRepository eventRepository;
    private final ShippingEventPublisher publisher;

    @Transactional
    public ShipmentEvent insert(ShipmentEvent event) {
        return eventRepository.saveAndFlush(event);
    }

    @Transactional
    public void complete(Shipment shipment, ShipmentEvent event, boolean delivered) {
        shipmentRepository.save(shipment);
        eventRepository.save(event);
        if (delivered) {
            publisher.publishDelivered(shipment, false);
        }
    }
}
