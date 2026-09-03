package com.shop.inventoryservice.mapper;

import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.common.core.exception.BusinessException;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class InventoryMapper {

    private final ModelMapper modelMapper;

    public InventoryMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
            inventory.getProductId(),
            inventory.getAvailableQuantity(),
            inventory.getReservedQuantity(),
            inventory.getLastUpdated(),
            inventory.getSafetyStockThreshold()
        );
    }

    public Inventory toEntity(InventoryUpsertRequest request) {
        Inventory inventory = modelMapper.map(request, Inventory.class);
        inventory.setId(null);
        inventory.setAvailableQuantity(request.availableQuantity());
        inventory.setReservedQuantity(0);
        return inventory;
    }

    public void partialUpdate(Inventory target, InventoryUpsertRequest request) {
        if (request.availableQuantity() != null) {
            // A10: refuse to drop available below already-committed/held stock — would let
            // reservations/orders oversell. Caller must release or commit reservations first.
            int reserved = target.getReservedQuantity() == null ? 0 : target.getReservedQuantity();
            if (request.availableQuantity() < reserved) {
                throw BusinessException.badRequest(
                    "inventory.available.below.reserved",
                    request.availableQuantity(), reserved);
            }
            target.setAvailableQuantity(request.availableQuantity());
        }
    }

    public ReservationResponse toReservationResponse(Reservation reservation) {
        return new ReservationResponse(
            reservation.getId(),
            reservation.getProductId(),
            reservation.getQuantity(),
            reservation.getStatus(),
            reservation.getCreatedAt(),
            reservation.getExpiresAt(),
            reservation.getCommittedAt(),
            reservation.getReleasedAt(),
            reservation.getOrderId()
        );
    }
}
