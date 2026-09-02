package com.shop.taxservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.response.TaxCalculateResponse;
import com.shop.taxservice.service.TaxCalculationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TaxCalculationController {

    private final TaxCalculationService taxCalculationService;

    @PostMapping(ApiPaths.TAX_CALCULATION)
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Audited(action = "tax.calculate", resourceType = "tax-calculation")
    public ApiResponse<TaxCalculateResponse> calculate(@Valid @RequestBody TaxCalculateRequest request) {
        return ApiResponse.ok(taxCalculationService.calculate(request));
    }
}
