package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.OrderReturnCreateRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import com.shop.orderservice.service.OrderReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class OrderReturnController {

    private final OrderReturnService orderReturnService;

    @PostMapping("/{orderId}/returns")
    public ApiResponse<OrderReturnResponse> requestReturn(
            @PathVariable UUID orderId,
            @Valid @RequestBody OrderReturnCreateRequest request) {
        return ApiResponse.ok(
                orderReturnService.requestReturn(currentUserId(), orderId, request),
                "Return request submitted successfully");
    }

    @GetMapping("/{orderId}/returns")
    public ApiResponse<List<OrderReturnResponse>> findByOrderId(@PathVariable UUID orderId) {
        return ApiResponse.ok(orderReturnService.findByOrderId(currentUserId(), orderId));
    }

    @GetMapping("/returns/me")
    public ApiResponse<PageResponse<OrderReturnResponse>> findMyReturns(
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        return ApiResponse.ok(orderReturnService.findMyReturns(currentUserId(), pageable));
    }

    private static UUID currentUserId() {
        return UUID.fromString(AuthenticatedUser.requireCurrent().id());
    }
}
