package com.shop.shippingservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.request.AssignTrackingRequest;
import com.shop.shippingservice.dto.request.ShipmentTransitionRequest;
import com.shop.shippingservice.dto.response.ShipmentResponse;
import com.shop.shippingservice.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.BACKOFFICE_SHIPMENTS)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BackofficeShipmentController {

    private final ShipmentService shipmentService;

    @GetMapping
    public ApiResponse<PageResponse<ShipmentResponse>> findAll(
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) Carrier carrier,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<ShipmentResponse> result = shipmentService.findAll(status, carrier, orderId, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ShipmentResponse> findById(@PathVariable UUID id) {
        return ApiResponse.ok(shipmentService.findById(id));
    }

    @PostMapping("/{id}/tracking")
    public ApiResponse<ShipmentResponse> assignTracking(@PathVariable UUID id,
                                                        @RequestBody AssignTrackingRequest request) {
        return ApiResponse.ok(shipmentService.assignTracking(id, request.trackingNumber()),
            "Tracking assigned successfully");
    }

    @PostMapping("/{id}/transition")
    public ApiResponse<ShipmentResponse> transition(@PathVariable UUID id,
                                                    @RequestBody ShipmentTransitionRequest request) {
        return ApiResponse.ok(shipmentService.transition(id, request.status()),
            "Shipment status updated successfully");
    }

    @PostMapping("/{id}/fail")
    public ApiResponse<ShipmentResponse> fail(@PathVariable UUID id) {
        return ApiResponse.ok(shipmentService.fail(id), "Shipment marked as failed");
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<ShipmentResponse> retry(@PathVariable UUID id) {
        return ApiResponse.ok(shipmentService.retry(id), "Shipment retry started");
    }
}
