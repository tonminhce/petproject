package com.shop.taxservice.dto.response;

import java.math.BigDecimal;

public record TaxCalculateResponse(BigDecimal taxAmount, BigDecimal appliedRate) {}
