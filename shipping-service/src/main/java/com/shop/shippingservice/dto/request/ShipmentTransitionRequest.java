package com.shop.shippingservice.dto.request;

import com.shop.shippingservice.constant.ShipmentStatus;

public record ShipmentTransitionRequest(ShipmentStatus status) {
}
