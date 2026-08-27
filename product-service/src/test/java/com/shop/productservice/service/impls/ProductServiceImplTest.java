package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Product;
import com.shop.productservice.entity.ProductStatus;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock ProductRepository repo;
    @Mock CategoryRepository categoryRepo;
    @Mock BrandRepository brandRepo;
    @Mock ProductMapper mapper;
    @Mock ProductEventPublisher publisher;
    @Mock AuditorAware<String> auditorAware;
    @InjectMocks ProductServiceImpl service;

    private ProductCreateRequest sampleCreate() {
        return new ProductCreateRequest("iPhone 15", "iphone-15", "desc", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null);
    }

    private Product sampleProduct() {
        return Product.builder().id(1L).title("iPhone 15").slug("iphone-15").sku("IP15-001")
            .priceUnit(new BigDecimal("999.00")).quantity(10).status(ProductStatus.ACTIVE).build();
    }

    @Test
    void findById_returnsProduct() {
        Product p = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null);
        when(repo.findWithRelationsById(1L)).thenReturn(Optional.of(p));
        when(mapper.toDetailResponse(p)).thenReturn(resp);

        assertThat(service.findById(1L)).isEqualTo(resp);
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(repo.findWithRelationsById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndPublishesEvent() {
        ProductCreateRequest req = sampleCreate();
        Product product = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null);
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(product);
        when(repo.save(product)).thenReturn(product);
        when(mapper.toDetailResponse(product)).thenReturn(resp);

        ProductDetailResponse result = service.create(req);

        assertThat(result).isEqualTo(resp);
        verify(publisher).publishCreated(product);
    }

    @Test
    void create_throwsConflictOnDuplicateSlug() {
        ProductCreateRequest req = sampleCreate();
        when(repo.existsBySlug("iphone-15")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void update_appliesPartialUpdateAndPublishes() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, "new desc", null,
            new BigDecimal("1099.00"), null, null, null, null, null, null, null);
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            "new desc", "IP15-001", new BigDecimal("1099.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null, null, null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(resp);

        ProductDetailResponse result = service.update(1L, req);

        assertThat(result.priceUnit()).isEqualByComparingTo("1099.00");
        verify(publisher).publishUpdated(existing);
    }

    @Test
    void delete_softDeletesWithActorAndPublishes() {
        Product existing = sampleProduct();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));

        service.delete(1L);

        assertThat(existing.isDeleted()).isTrue();
        assertThat(existing.getDeletedBy()).isEqualTo("alice");
        verify(repo).save(existing);
        verify(publisher).publishDeleted(existing);
    }

    @Test
    void findAll_returnsPagedSummary() {
        Product p = sampleProduct();
        when(repo.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(p)));
        when(mapper.toSummaryResponse(p)).thenReturn(
            new ProductSummaryResponse(
                1L, "iPhone 15", "iphone-15", "IP15-001",
                new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null));

        var result = service.findAll(new ProductFilter(null, null, null), PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
    }
}