package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.dto.request.ProductVariantCreateRequest;
import com.shop.productservice.dto.request.ProductVariantUpdateRequest;
import com.shop.productservice.dto.response.ProductVariantResponse;
import com.shop.productservice.entity.Product;
import com.shop.productservice.entity.ProductVariant;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.repository.ProductVariantRepository;
import com.shop.productservice.service.ProductVariantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductVariantServiceImpl implements ProductVariantService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ProductVariantResponse> findByProductId(UUID productId) {
        ensureProductExists(productId);
        return productVariantRepository.findByProductIdAndDeletedFalse(productId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductVariantResponse findById(UUID productId, UUID variantId) {
        ensureProductExists(productId);
        ProductVariant variant = productVariantRepository.findByIdAndProductIdAndDeletedFalse(variantId, productId)
            .orElseThrow(() -> BusinessException.notFound("product.variant.not.found", variantId));
        return toResponse(variant);
    }

    @Override
    @Transactional
    public ProductVariantResponse create(UUID productId, ProductVariantCreateRequest request) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, productId));

        if (productVariantRepository.existsBySkuAndDeletedFalse(request.sku())) {
            throw BusinessException.of(ErrorCode.PRODUCT_SKU_EXISTS, request.sku());
        }

        ProductVariant variant = ProductVariant.builder()
            .product(product)
            .sku(request.sku().trim())
            .title(request.title().trim())
            .price(request.price())
            .quantity(request.quantity())
            .attributes(request.attributes())
            .imageUrl(request.imageUrl())
            .build();

        ProductVariant saved = productVariantRepository.save(variant);
        log.info("Created variant {} for product {}", saved.getId(), productId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public ProductVariantResponse update(UUID productId, UUID variantId, ProductVariantUpdateRequest request) {
        ensureProductExists(productId);
        ProductVariant variant = productVariantRepository.findByIdAndProductIdAndDeletedFalse(variantId, productId)
            .orElseThrow(() -> BusinessException.notFound("product.variant.not.found", variantId));

        if (request.sku() != null && !request.sku().isBlank()) {
            String newSku = request.sku().trim();
            if (!newSku.equalsIgnoreCase(variant.getSku())
                && productVariantRepository.existsBySkuAndIdNotAndDeletedFalse(newSku, variantId)) {
                throw BusinessException.of(ErrorCode.PRODUCT_SKU_EXISTS, newSku);
            }
            variant.setSku(newSku);
        }

        if (request.title() != null && !request.title().isBlank()) {
            variant.setTitle(request.title().trim());
        }
        if (request.price() != null) {
            variant.setPrice(request.price());
        }
        if (request.quantity() != null) {
            variant.setQuantity(request.quantity());
        }
        if (request.attributes() != null) {
            variant.setAttributes(request.attributes());
        }
        if (request.imageUrl() != null) {
            variant.setImageUrl(request.imageUrl());
        }

        ProductVariant saved = productVariantRepository.save(variant);
        log.info("Updated variant {} for product {}", saved.getId(), productId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID productId, UUID variantId) {
        ensureProductExists(productId);
        ProductVariant variant = productVariantRepository.findByIdAndProductIdAndDeletedFalse(variantId, productId)
            .orElseThrow(() -> BusinessException.notFound("product.variant.not.found", variantId));
        variant.markDeleted("system");
        productVariantRepository.save(variant);
        log.info("Deleted variant {} for product {}", variantId, productId);
    }

    private void ensureProductExists(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, productId);
        }
    }

    private ProductVariantResponse toResponse(ProductVariant variant) {
        return new ProductVariantResponse(
            variant.getId(),
            variant.getProduct().getId(),
            variant.getSku(),
            variant.getTitle(),
            variant.getPrice(),
            variant.getQuantity(),
            variant.getAttributes(),
            variant.getImageUrl(),
            variant.getCreatedAt(),
            variant.getUpdatedAt()
        );
    }
}
