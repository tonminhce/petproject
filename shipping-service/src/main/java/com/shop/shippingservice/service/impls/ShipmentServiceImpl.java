package com.shop.shippingservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.carrier.CarrierFactory;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.OrderLifecycleEvent;
import com.shop.shippingservice.dto.response.ShipmentResponse;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.ShippingMetrics;
import com.shop.shippingservice.service.ShipmentService;
import com.shop.shippingservice.service.ShipmentStateMachine;
import com.shop.shippingservice.service.ShipmentWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ShipmentServiceImpl implements ShipmentService {

    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final ShipmentRepository repository;
    private final ShipmentWriter writer;
    private final CarrierAdapter defaultCarrierAdapter;
    private final Clock clock;
    private final ShippingMetrics metrics;
    private final CarrierFactory carrierFactory;

    @Autowired
    public ShipmentServiceImpl(
            ShipmentRepository repository,
            ShipmentWriter writer,
            CarrierAdapter defaultCarrierAdapter,
            Clock clock,
            ShippingMetrics metrics,
            @Autowired(required = false) CarrierFactory carrierFactory) {
        this.repository = repository;
        this.writer = writer;
        this.defaultCarrierAdapter = defaultCarrierAdapter;
        this.clock = clock;
        this.metrics = metrics;
        this.carrierFactory = carrierFactory;
    }

    public ShipmentServiceImpl(
            ShipmentRepository repository,
            ShipmentWriter writer,
            CarrierAdapter defaultCarrierAdapter,
            Clock clock,
            ShippingMetrics metrics) {
        this(repository, writer, defaultCarrierAdapter, clock, metrics, null);
    }

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
        CarrierAdapter adapter = (carrierFactory != null)
                ? carrierFactory.getDefaultCarrier()
                : defaultCarrierAdapter;
        Shipment shipment = Shipment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .carrier(adapter.carrier())
                .build();
        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(orderId);
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

    @Override
    public Page<ShipmentResponse> findAll(ShipmentStatus status, Carrier carrier, UUID orderId, Pageable pageable) {
        if (orderId != null) {
            List<ShipmentResponse> content = repository.findByOrderId(orderId)
                    .map(shipment -> List.<ShipmentResponse>of(ShipmentResponse.from(shipment)))
                    .orElseGet(List::of);
            return new PageImpl<>(content, pageable, content.size());
        }
        Page<Shipment> result;
        if (status != null) {
            result = repository.findAllByStatusOrderByCreatedAtDesc(status, pageable);
        } else if (carrier != null) {
            result = repository.findAllByCarrierOrderByCreatedAtDesc(carrier, pageable);
        } else {
            result = repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return result.map(ShipmentResponse::from);
    }

    @Override
    public ShipmentResponse findById(UUID id) {
        return ShipmentResponse.from(requireShipment(id));
    }

    @Override
    public ShipmentResponse assignTracking(UUID id, String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw BusinessException.of(ErrorCode.TRACKING_REQUIRED);
        }
        Shipment shipment = requireShipment(id);
        if (shipment.getStatus() != ShipmentStatus.CREATED || shipment.getCarrier() != Carrier.MANUAL) {
            throw BusinessException.of(ErrorCode.SHIPMENT_INVALID_TRANSITION,
                    shipment.getStatus(), ShipmentStatus.PICKED_UP);
        }
        shipment.setTrackingNumber(trackingNumber.trim());
        shipment.setLastCarrierUpdate(clock.instant());
        return advance(shipment, ShipmentStatus.PICKED_UP);
    }

    @Override
    public ShipmentResponse transition(UUID id, ShipmentStatus status) {
        Shipment shipment = requireShipment(id);
        return advance(shipment, status);
    }

    @Override
    public ShipmentResponse fail(UUID id) {
        Shipment shipment = requireShipment(id);
        return advance(shipment, ShipmentStatus.DELIVERY_FAILED);
    }

    @Override
    public ShipmentResponse retry(UUID id) {
        Shipment shipment = requireShipment(id);
        return advance(shipment, ShipmentStatus.IN_TRANSIT);
    }

    private Shipment requireShipment(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.SHIPMENT_NOT_FOUND, id));
    }

    private ShipmentResponse advance(Shipment shipment, ShipmentStatus target) {
        ShipmentStatus from = shipment.getStatus();
        ShipmentStatus next = ShipmentStateMachine.transition(from, target);
        shipment.setPreviousStatus(from);
        shipment.setStatus(next);
        metrics.recordAdvance(from, next);
        if (next == ShipmentStatus.DELIVERY_FAILED) {
            metrics.recordFailed();
        }
        if (next == ShipmentStatus.DELIVERED) {
            shipment.setDeliveredAt(clock.instant());
            metrics.recordDelivered(false);
            return ShipmentResponse.from(writer.saveDelivered(shipment, false));
        }
        return ShipmentResponse.from(writer.save(shipment));
    }
}
