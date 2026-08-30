package com.shop.taxservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record TaxRateResponse(UUID id, UUID taxClassId, String country, String postalCode, BigDecimal ratePct) {}
