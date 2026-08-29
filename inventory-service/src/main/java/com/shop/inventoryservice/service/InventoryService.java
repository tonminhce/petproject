package com.shop.inventoryservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface InventoryService {

    PageResponse<InventoryResponse> findAll(Pageable pageable);

    InventoryResponse findById(UUID productId);

    InventoryResponse create(InventoryUpsertRequest request);

    InventoryResponse update(UUID productId, InventoryUpsertRequest request);

    void delete(UUID productId);

    ReservationResponse reserve(UUID productId, ReserveRequest request);

    void commit(UUID reservationId);

    void release(UUID reservationId);

    void releaseCommitted(UUID reservationId);
}
