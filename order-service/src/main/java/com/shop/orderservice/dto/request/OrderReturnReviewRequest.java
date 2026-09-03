package com.shop.orderservice.dto.request;

import com.shop.orderservice.constant.ReturnStatus;
import jakarta.validation.constraints.NotNull;

public record OrderReturnReviewRequest(
    @NotNull(message = "Status is required")
    ReturnStatus status,

    String adminNotes
) {}
