package com.shop.orderservice.service;

import java.util.UUID;

/**
 * (type, id) pair for compensation tracking — spec D10. Promotion and inventory
 * reservation ids are both UUIDs, indistinguishable after the fact; the type tag
 * decides which client performs the rollback.
 */
public record CompensationTarget(Type type, UUID id) {

    public enum Type { PROMOTION, INVENTORY }
}
