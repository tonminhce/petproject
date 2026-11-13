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

/**
 * Internal, SERVICE-role view on the stock reservations of a product (F-060).
 *
 * <p>inventory-service refuses to delete an inventory row that has PENDING or
 * COMMITTED reservations. The symmetric guard was missing on the catalogue
 * side: product-service soft-deleted a product unconditionally, so a product
 * could leave the catalogue while its stock was reserved or committed and the
 * inventory row survived as an orphan. The catalogue lives in another
 * database, so it cannot join the reservations table - it asks here.</p>
 */
@RestController
@RequestMapping(ApiPaths.INTERNAL_INVENTORY_RESERVATIONS)
@RequiredArgsConstructor
public class InternalInventoryController {

    private final InventoryService inventoryService;

    /**
     * @return {@code {productId, activeReservationCount}} — the number of
     *         PENDING + COMMITTED reservations (0 = no live stock claim)
     */
    @GetMapping("/{productId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ApiResponse<ActiveReservationCountResponse> activeReservations(@PathVariable UUID productId) {
        return ApiResponse.ok(new ActiveReservationCountResponse(
                productId, inventoryService.activeReservationCount(productId)));
    }
}
