package com.shop.productservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.dto.request.ProductVariantCreateRequest;
import com.shop.productservice.dto.request.ProductVariantUpdateRequest;
import com.shop.productservice.dto.response.ProductVariantResponse;
import com.shop.productservice.entity.Product;
import com.shop.productservice.entity.ProductVariant;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.repository.ProductVariantRepository;
import com.shop.productservice.service.impls.ProductVariantServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    private ProductVariantService service;

    private final UUID productId = UUID.randomUUID();
    private final UUID variantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductVariantServiceImpl(productRepository, productVariantRepository);
    }

    @Test
    void findByProductId_success() {
        when(productRepository.existsById(productId)).thenReturn(true);
        Product product = Product.builder().id(productId).build();
        ProductVariant variant = ProductVariant.builder()
            .id(variantId).product(product).sku("SKU-1").title("Variant 1")
            .price(BigDecimal.valueOf(100)).quantity(10).build();
        when(productVariantRepository.findByProductIdAndDeletedFalse(productId)).thenReturn(List.of(variant));

        List<ProductVariantResponse> result = service.findByProductId(productId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().sku()).isEqualTo("SKU-1");
    }

    @Test
    void create_duplicateSku_throwsException() {
        when(productRepository.findById(productId))
            .thenReturn(Optional.of(Product.builder().id(productId).build()));
        when(productVariantRepository.existsBySkuAndDeletedFalse("SKU-1")).thenReturn(true);

        ProductVariantCreateRequest request = new ProductVariantCreateRequest(
            "SKU-1", "Variant 1", BigDecimal.valueOf(100), 10, null, null);

        assertThatThrownBy(() -> service.create(productId, request))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("PRD-2005"));
    }

    @Test
    void create_success() {
        Product product = Product.builder().id(productId).build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySkuAndDeletedFalse("SKU-NEW")).thenReturn(false);

        ProductVariant variant = ProductVariant.builder()
            .id(variantId).product(product).sku("SKU-NEW").title("New Variant")
            .price(BigDecimal.valueOf(120)).quantity(5).build();
        when(productVariantRepository.save(any(ProductVariant.class))).thenReturn(variant);

        ProductVariantCreateRequest request = new ProductVariantCreateRequest(
            "SKU-NEW", "New Variant", BigDecimal.valueOf(120), 5, null, null);

        ProductVariantResponse response = service.create(productId, request);

        assertThat(response.id()).isEqualTo(variantId);
        assertThat(response.sku()).isEqualTo("SKU-NEW");
    }

    @Test
    void delete_success() {
        when(productRepository.existsById(productId)).thenReturn(true);
        Product product = Product.builder().id(productId).build();
        ProductVariant variant = ProductVariant.builder()
            .id(variantId).product(product).sku("SKU-1").title("Variant 1")
            .price(BigDecimal.valueOf(100)).quantity(10).build();
        when(productVariantRepository.findByIdAndProductIdAndDeletedFalse(variantId, productId))
            .thenReturn(Optional.of(variant));

        service.delete(productId, variantId);

        assertThat(variant.isDeleted()).isTrue();
        verify(productVariantRepository).save(variant);
    }
}
