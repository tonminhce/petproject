package com.shop.taxservice.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record TaxRateRequest(
    @NotNull UUID taxClassId,
    @Pattern(regexp = "^[A-Z]{2}$") String country,
    String postalCode,
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal ratePct
) {}
