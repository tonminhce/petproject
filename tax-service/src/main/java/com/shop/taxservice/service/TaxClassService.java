package com.shop.taxservice.service;

import com.shop.taxservice.dto.request.TaxClassRequest;
import com.shop.taxservice.dto.response.TaxClassResponse;

import java.util.List;
import java.util.UUID;

public interface TaxClassService {

    TaxClassResponse create(TaxClassRequest request);

    TaxClassResponse update(UUID id, TaxClassRequest request);

    TaxClassResponse get(UUID id);

    List<TaxClassResponse> list();

    void delete(UUID id);
}
