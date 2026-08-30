package com.shop.promotionservice.service;

import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.dto.request.CampaignRequest;
import com.shop.promotionservice.dto.response.CampaignResponse;
import com.shop.promotionservice.dto.response.CampaignUsageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Backoffice campaign CRUD (spec §4.2).
 */
public interface CampaignService {

    Page<CampaignResponse> findAll(CampaignStatus status, Pageable pageable);

    CampaignResponse findById(UUID id);

    CampaignResponse create(CampaignRequest request);

    CampaignResponse update(UUID id, CampaignRequest request);

    void delete(UUID id);

    Page<CampaignUsageResponse> usages(UUID campaignId, Pageable pageable);
}
