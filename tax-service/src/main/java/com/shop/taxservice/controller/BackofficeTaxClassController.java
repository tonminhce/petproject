package com.shop.taxservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.taxservice.dto.request.TaxClassRequest;
import com.shop.taxservice.dto.response.TaxClassResponse;
import com.shop.taxservice.service.TaxClassService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.BACKOFFICE_TAX_CLASSES)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BackofficeTaxClassController {

    private final TaxClassService taxClassService;

    @GetMapping
    public ApiResponse<List<TaxClassResponse>> list() {
        return ApiResponse.ok(taxClassService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<TaxClassResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(taxClassService.get(id));
    }

    @PostMapping
    @Audited(action = "tax-class.create", resourceType = "tax-class")
    public ApiResponse<TaxClassResponse> create(@Valid @RequestBody TaxClassRequest request) {
        return ApiResponse.ok(taxClassService.create(request), "Tax class created successfully");
    }

    @PutMapping("/{id}")
    @Audited(action = "tax-class.update", resourceType = "tax-class")
    public ApiResponse<TaxClassResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody TaxClassRequest request) {
        return ApiResponse.ok(taxClassService.update(id, request), "Tax class updated successfully");
    }

    @DeleteMapping("/{id}")
    @Audited(action = "tax-class.delete", resourceType = "tax-class")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        taxClassService.delete(id);
        return ApiResponse.message("Tax class deleted successfully");
    }
}
