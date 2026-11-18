package com.shop.inventoryservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.inventoryservice.dto.response.ActiveReservationCountResponse;
import com.shop.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.INTERNAL_INVENTORY_RESERVATIONS)
@RequiredArgsConstructor
public class InternalInventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ApiResponse<ActiveReservationCountResponse> activeReservations(@PathVariable UUID productId) {
        return ApiResponse.ok(new ActiveReservationCountResponse(
                productId, inventoryService.activeReservationCount(productId)));
    }
}
