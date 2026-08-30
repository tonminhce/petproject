package com.shop.taxservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record TaxClassResponse(UUID id, String name, BigDecimal defaultRatePct) {}
