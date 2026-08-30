package com.shop.taxservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.taxservice.dto.request.TaxRateRequest;
import com.shop.taxservice.dto.response.TaxRateResponse;
import com.shop.taxservice.service.TaxRateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.BACKOFFICE_TAX_RATES)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BackofficeTaxRateController {

    private final TaxRateService taxRateService;

    @GetMapping
    public ApiResponse<List<TaxRateResponse>> list(@RequestParam(required = false) UUID classId) {
        return ApiResponse.ok(taxRateService.list(classId));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaxRateResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(taxRateService.get(id));
    }

    @PostMapping
    public ApiResponse<TaxRateResponse> create(@Valid @RequestBody TaxRateRequest request) {
        return ApiResponse.ok(taxRateService.create(request), "Tax rate created successfully");
    }

    @PutMapping("/{id}")
    public ApiResponse<TaxRateResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody TaxRateRequest request) {
        return ApiResponse.ok(taxRateService.update(id, request), "Tax rate updated successfully");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        taxRateService.delete(id);
        return ApiResponse.message("Tax rate deleted successfully");
    }
}
