package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;

public interface InventoryEventPublisher {

    void publishReserved(Inventory inventory, Reservation reservation);

    void publishCommitted(Inventory inventory, Reservation reservation);

    void publishReleased(Inventory inventory, Reservation reservation, String previousStatus);

    void publishAdjusted(Inventory inventory);

    void publishDeleted(Inventory inventory);

    void publishLowStock(Inventory inventory);
}
