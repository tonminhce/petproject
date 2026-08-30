package com.shop.taxservice.service;

import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.response.TaxCalculateResponse;

public interface TaxCalculationService {

    TaxCalculateResponse calculate(TaxCalculateRequest req);
}
