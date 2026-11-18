package com.shop.promotionservice.repository;

import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.entity.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    Optional<Campaign> findByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    boolean existsByCode(String code);

    Page<Campaign> findAllByStatus(CampaignStatus status, Pageable pageable);
}
