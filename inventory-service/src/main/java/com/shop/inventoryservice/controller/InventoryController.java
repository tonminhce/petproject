package com.shop.inventoryservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.INVENTORY)
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ReservationService reservationService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<InventoryResponse>> findAll(
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return ApiResponse.ok(inventoryService.findAll(pageable));
    }

    @GetMapping("/{productId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<InventoryResponse> findById(@PathVariable UUID productId) {
        return ApiResponse.ok(inventoryService.findById(productId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<InventoryResponse> create(@Valid @RequestBody InventoryUpsertRequest request) {
        return ApiResponse.ok(inventoryService.create(request), "Inventory created successfully");
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<InventoryResponse> update(@PathVariable UUID productId,
                                                  @Valid @RequestBody InventoryUpsertRequest request) {
        return ApiResponse.ok(inventoryService.update(productId, request), "Inventory updated successfully");
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable UUID productId) {
        inventoryService.delete(productId);
        return ApiResponse.message("Inventory deleted successfully");
    }

    @PostMapping("/{productId}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "inventory.reserve", resourceType = "reservation")
    public ApiResponse<ReservationResponse> reserve(@PathVariable UUID productId,
                                                     @Valid @RequestBody ReserveRequest request) {
        return ApiResponse.ok(reservationService.reserveWithRetry(productId, request));
    }

    @PostMapping("/reservations/{reservationId}/commit")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "inventory.commit", resourceType = "reservation")
    public ApiResponse<Void> commit(@PathVariable UUID reservationId) {
        reservationService.commitWithRetry(reservationId);
        return ApiResponse.message("Reservation committed successfully");
    }

    @PostMapping("/reservations/{reservationId}/release")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "inventory.release", resourceType = "reservation")
    public ApiResponse<Void> release(@PathVariable UUID reservationId) {
        reservationService.releaseWithRetry(reservationId);
        return ApiResponse.message("Reservation released successfully");
    }

    @PostMapping("/reservations/{reservationId}/release-committed")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "inventory.release-committed", resourceType = "reservation")
    public ApiResponse<Void> releaseCommitted(@PathVariable UUID reservationId) {
        reservationService.releaseCommittedWithRetry(reservationId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reservations/{reservationId}/state")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ApiResponse<ReservationResponse> state(@PathVariable UUID reservationId) {
        return ApiResponse.ok(inventoryService.getState(reservationId));
    }
}
