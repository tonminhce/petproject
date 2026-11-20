package com.shop.paymentservice.dto;

import com.shop.paymentservice.constant.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentRefundRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank String currency
) {
}
