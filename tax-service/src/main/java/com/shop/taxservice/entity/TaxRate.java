package com.shop.taxservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.taxservice.constant.TaxConstants;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "tax_rates")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxRate extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tax_class_id", nullable = false)
    private UUID taxClassId;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "postal_code", length = 16)
    private String postalCode;

    @Column(name = "rate_pct", nullable = false, precision = 5, scale = 2)
    @jakarta.validation.constraints.DecimalMax(TaxConstants.MAX_PERCENTAGE)
    private BigDecimal ratePct;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;
}
