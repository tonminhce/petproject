package com.shop.taxservice.service;

import com.shop.taxservice.dto.request.TaxRateRequest;
import com.shop.taxservice.dto.response.TaxRateResponse;

import java.util.List;
import java.util.UUID;

public interface TaxRateService {

    TaxRateResponse create(TaxRateRequest request);

    TaxRateResponse update(UUID id, TaxRateRequest request);

    TaxRateResponse get(UUID id);

    List<TaxRateResponse> list(UUID classId);

    void delete(UUID id);
}
