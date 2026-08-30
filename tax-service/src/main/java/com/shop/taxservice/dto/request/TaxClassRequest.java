package com.shop.taxservice.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TaxClassRequest(
    @NotBlank String name,
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal defaultRatePct
) {}
