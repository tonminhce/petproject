package com.shop.orderservice.service.impls;

import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockReservationServiceImpl implements StockReservationService {

    private final InventoryServiceClient inventoryClient;

    @Override
    public UUID reserve(UUID productId, ReserveRequest request) {
        return inventoryClient.reserve(productId, request);
    }

    @Override
    public void release(UUID reservationId) {
        inventoryClient.release(reservationId);
    }

    @Override
    public void releaseCommitted(UUID reservationId) {
        inventoryClient.releaseCommitted(reservationId);
    }
}
