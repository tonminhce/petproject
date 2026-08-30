package com.shop.shippingservice.service.impls;

import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.OrderLifecycleEvent;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.ShipmentService;
import com.shop.shippingservice.service.ShipmentStateMachine;
import com.shop.shippingservice.service.ShipmentWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentServiceImpl implements ShipmentService {

    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final ShipmentRepository repository;
    private final ShipmentWriter writer;
    private final CarrierAdapter carrierAdapter;

    @Override
    public void handleOrderEvent(OrderLifecycleEvent event) {
        String status = event.getStatus();
        if (STATUS_CONFIRMED.equals(status)) {
            createShipment(event);
        } else if (STATUS_CANCELLED.equals(status)) {
            cancelShipment(event);
        }
    }

    private void createShipment(OrderLifecycleEvent event) {
        UUID orderId = event.getOrderId();
        if (repository.existsByOrderId(orderId)) {
            log.info("Shipment for order {} already exists, skipping", orderId);
            return;
        }
        Shipment shipment = Shipment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .carrier(carrierAdapter.carrier())
                .build();
        CarrierAdapter.ShipmentDraft draft = carrierAdapter.createShipment(orderId);
        shipment.setTrackingNumber(draft.trackingNumber());
        shipment.setStatus(draft.initialStatus());
        shipment.setAutoDelivered(draft.initialStatus() == ShipmentStatus.DELIVERED);
        try {
            writer.insert(shipment);
        } catch (DataIntegrityViolationException e) {
            log.info("Shipment for order {} already persisted by a concurrent consumer, skipping", orderId);
        }
    }

    private void cancelShipment(OrderLifecycleEvent event) {
        repository.findByOrderId(event.getOrderId()).ifPresent(shipment -> {
            if (shipment.getStatus() != ShipmentStatus.CREATED) {
                log.info("Shipment {} for cancelled order {} is {}, leaving untouched",
                        shipment.getId(), event.getOrderId(), shipment.getStatus());
                return;
            }
            shipment.setPreviousStatus(shipment.getStatus());
            shipment.setStatus(ShipmentStateMachine.transition(shipment.getStatus(), ShipmentStatus.CANCELLED));
            writer.save(shipment);
        });
    }
}
