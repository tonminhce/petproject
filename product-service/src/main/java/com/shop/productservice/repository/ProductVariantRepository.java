package com.shop.productservice.repository;

import com.shop.productservice.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    List<ProductVariant> findByProductIdAndDeletedFalse(UUID productId);

    Optional<ProductVariant> findByIdAndProductIdAndDeletedFalse(UUID id, UUID productId);

    Optional<ProductVariant> findBySkuAndDeletedFalse(String sku);

    boolean existsBySkuAndDeletedFalse(String sku);

    boolean existsBySkuAndIdNotAndDeletedFalse(String sku, UUID id);
}
