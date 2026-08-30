package com.shop.promotionservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.promotionservice.constant.CampaignStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaign")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Campaign extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_order_amount", precision = 19, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "total_budget", precision = 19, scale = 2)
    private BigDecimal totalBudget;

    @Column(name = "per_user_limit", nullable = false)
    @Builder.Default
    private Integer perUserLimit = 1;

    /**
     * Optimistic-lock guard for concurrent reserve/commit/release (mirrors
     * order-service Order / inventory-service precedent).
     */
    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.INACTIVE;

    public void activate() {
        this.status = CampaignStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = CampaignStatus.INACTIVE;
    }
}
