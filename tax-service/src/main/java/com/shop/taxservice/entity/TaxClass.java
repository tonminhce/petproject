package com.shop.taxservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.taxservice.constant.TaxConstants;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "tax_classes")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxClass extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "default_rate_pct", nullable = false, precision = 5, scale = 2)
    @DecimalMax(TaxConstants.MAX_PERCENTAGE)
    private BigDecimal defaultRatePct;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;
}
