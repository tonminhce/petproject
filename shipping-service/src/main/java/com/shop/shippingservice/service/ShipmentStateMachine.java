package com.shop.shippingservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.shippingservice.constant.ShipmentStatus;

import java.util.Map;
import java.util.Set;

public final class ShipmentStateMachine {

    private static final Set<Map.Entry<ShipmentStatus, ShipmentStatus>> LEGAL_TRANSITIONS = Set.of(
            Map.entry(ShipmentStatus.CREATED, ShipmentStatus.PICKED_UP),
            Map.entry(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT),
            Map.entry(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY),
            Map.entry(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED),
            Map.entry(ShipmentStatus.PICKED_UP, ShipmentStatus.DELIVERY_FAILED),
            Map.entry(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERY_FAILED),
            Map.entry(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_FAILED),
            Map.entry(ShipmentStatus.DELIVERY_FAILED, ShipmentStatus.IN_TRANSIT),
            Map.entry(ShipmentStatus.CREATED, ShipmentStatus.CANCELLED),
            Map.entry(ShipmentStatus.PICKED_UP, ShipmentStatus.CANCELLED),
            Map.entry(ShipmentStatus.IN_TRANSIT, ShipmentStatus.CANCELLED),
            Map.entry(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.CANCELLED));

    private ShipmentStateMachine() {
    }

    public static ShipmentStatus transition(ShipmentStatus from, ShipmentStatus to) {
        if (from == null || to == null || !LEGAL_TRANSITIONS.contains(Map.entry(from, to))) {
            throw BusinessException.of(ErrorCode.SHIPMENT_INVALID_TRANSITION, from, to);
        }
        return to;
    }
}
