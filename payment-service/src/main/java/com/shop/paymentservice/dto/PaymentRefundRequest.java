package com.shop.paymentservice.dto;

import com.shop.paymentservice.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRefundRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @ValidCurrency String currency,
        @NotNull UUID refundReference
) {
}
