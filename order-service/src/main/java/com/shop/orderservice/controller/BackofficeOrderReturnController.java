package com.shop.orderservice.controller;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import com.shop.orderservice.service.OrderReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/backoffice/returns")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackofficeOrderReturnController {

    private final OrderReturnService orderReturnService;

    @PutMapping("/{returnId}/review")
    @Audited(action = "order_return.review", resourceType = "order_return")
    public ApiResponse<OrderReturnResponse> reviewReturn(
            @PathVariable UUID returnId,
            @Valid @RequestBody OrderReturnReviewRequest request) {
        String adminUsername = AuthenticatedUser.current()
                .map(AuthenticatedUser::username)
                .orElse("admin");
        return ApiResponse.ok(
                orderReturnService.reviewReturn(returnId, adminUsername, request),
                "Order return reviewed successfully");
    }
}
