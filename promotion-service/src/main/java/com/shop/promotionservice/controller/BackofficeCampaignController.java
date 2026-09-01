package com.shop.promotionservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.dto.request.CampaignRequest;
import com.shop.promotionservice.dto.response.CampaignResponse;
import com.shop.promotionservice.dto.response.CampaignUsageResponse;
import com.shop.promotionservice.service.CampaignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

import java.util.UUID;

/**
 * Backoffice campaign CRUD (spec §4.2). ADMIN-only — class-level gate per the
 * fleet hardening direction (T8 review finding: reservation controller keeps
 * per-endpoint because two endpoints are SERVICE-visible; every route here is
 * backoffice, so one gate covers all).
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_PROMOTIONS)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BackofficeCampaignController {

    private final CampaignService campaignService;

    @GetMapping
    public ApiResponse<PageResponse<CampaignResponse>> findAll(
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        // Cap page size — PageableConstant.MAX_PAGE_SIZE guards against ?size=100000 dumps.
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<CampaignResponse> result = campaignService.findAll(status, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ApiResponse<CampaignResponse> findById(@PathVariable UUID id) {
        return ApiResponse.ok(campaignService.findById(id));
    }

    @PostMapping
    @Audited(action = "campaign.create", resourceType = "campaign")
    public ApiResponse<CampaignResponse> create(@Valid @RequestBody CampaignRequest request) {
        return ApiResponse.ok(campaignService.create(request), "Campaign created successfully");
    }

    @PutMapping("/{id}")
    @Audited(action = "campaign.update", resourceType = "campaign")
    public ApiResponse<CampaignResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody CampaignRequest request) {
        return ApiResponse.ok(campaignService.update(id, request), "Campaign updated successfully");
    }

    @DeleteMapping("/{id}")
    @Audited(action = "campaign.delete", resourceType = "campaign")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        campaignService.delete(id);
        return ApiResponse.message("Campaign deleted successfully");
    }

    @GetMapping("/{id}/usages")
    public ApiResponse<PageResponse<CampaignUsageResponse>> usages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<CampaignUsageResponse> result = campaignService.usages(id, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }
}
