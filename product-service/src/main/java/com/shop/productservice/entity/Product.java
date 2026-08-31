package com.shop.productservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.productservice.constant.ProductStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "products")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(name = "price_unit", nullable = false, precision = 15, scale = 2)
    private BigDecimal priceUnit;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(precision = 8, scale = 3)
    private BigDecimal weight;

    @Column(length = 50)
    private String dimensions;

    // Denormalized rating stars (spec D5) — copied from rating lifecycle
    // events by ProductRatingService, never recomputed here.
    @Column(name = "avg_rating", precision = 3, scale = 2)
    private BigDecimal avgRating;

    // @Builder.Default: Lombok builders must produce 0 (never null) or every
    // plain insert would violate the NOT NULL column.
    @Builder.Default
    @Column(name = "rating_count", nullable = false)
    private Integer ratingCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;
}