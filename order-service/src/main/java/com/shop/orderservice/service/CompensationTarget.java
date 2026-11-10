package com.shop.orderservice.service;

import java.util.UUID;

public record CompensationTarget(Type type, UUID id) {

    public enum Type { PROMOTION, INVENTORY }
}
