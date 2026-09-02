package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.client.MediaHeadClient;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Product;
import com.shop.productservice.constant.ProductStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    @Mock MediaHeadClient mediaHeadClient;
    @InjectMocks ProductServiceImpl service;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BRAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private ProductCreateRequest sampleCreate() {
        return new ProductCreateRequest("iPhone 15", "iphone-15", "desc", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null, null);
    }

    private Product sampleProduct() {
        return Product.builder().id(ID).title("iPhone 15").slug("iphone-15").sku("IP15-001")
            .priceUnit(new BigDecimal("999.00")).quantity(10).status(ProductStatus.ACTIVE)
            .avgRating(new BigDecimal("0.00")).ratingCount(0).build();
    }

    @Test
    void findById_returnsProduct() {
        Product p = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(ID, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null, null, null, null);
        when(repo.findWithRelationsById(ID)).thenReturn(Optional.of(p));
        when(mapper.toDetailResponse(p)).thenReturn(resp);

        assertThat(service.findById(ID)).isEqualTo(resp);
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(repo.findWithRelationsById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(ID)).isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndPublishesEvent() {
        ProductCreateRequest req = sampleCreate();
        Product product = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(ID, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null, null, null, null);
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
    void create_throwsCategoryNotFoundWhenFkMissing() {
        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", "desc", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, CATEGORY_ID, null);
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2003"));
        verifyNoInteractions(publisher);
        verify(repo, never()).save(any());
    }

    @Test
    void create_throwsBrandNotFoundWhenFkMissing() {
        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", "desc", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null, BRAND_ID);
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(brandRepo.findById(BRAND_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2006"));
        verifyNoInteractions(publisher);
        verify(repo, never()).save(any());
    }

    @Test
    void update_throwsCategoryNotFoundWhenFkMissing() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, null,
            null, null, null, null, null, null, null, CATEGORY_ID, null, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ID, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2003"));
        verifyNoInteractions(publisher);
    }

    @Test
    void update_throwsBrandNotFoundWhenFkMissing() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, null,
            null, null, null, null, null, null, null, null, BRAND_ID, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(brandRepo.findById(BRAND_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ID, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2006"));
        verifyNoInteractions(publisher);
    }

    @Test
    void update_throwsConflictOnDuplicateSlug() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, "taken", null, null,
            null, null, null, null, null, null, null, null, null, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.existsBySlugAndIdNot("taken", ID)).thenReturn(true);

        assertThatThrownBy(() -> service.update(ID, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2004"));
    }

    @Test
    void update_throwsConflictOnDuplicateSku() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, "taken-sku",
            null, null, null, null, null, null, null, null, null, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.existsBySkuAndIdNot("taken-sku", ID)).thenReturn(true);

        assertThatThrownBy(() -> service.update(ID, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2005"));
    }

    @Test
    void update_appliesPartialUpdateAndPublishes() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, "new desc", null,
            new BigDecimal("1099.00"), null, null, null, null, null, null, null, null, false);
        ProductDetailResponse resp = new ProductDetailResponse(ID, "iPhone 15", "iphone-15",
            "new desc", "IP15-001", new BigDecimal("1099.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null, null, null, null, null, null, null);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(resp);

        ProductDetailResponse result = service.update(ID, req);

        assertThat(result.priceUnit()).isEqualByComparingTo("1099.00");
        verify(publisher).publishUpdated(existing);
    }

    @Test
    void delete_softDeletesWithActorAndPublishes() {
        Product existing = sampleProduct();
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));

        service.delete(ID);

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
                ID, "iPhone 15", "iphone-15", "IP15-001",
                new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null));

        var result = service.findAll(new ProductFilter(null, null, null), PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
    }

    // --- media epic spec D5 — Option C write-time gate ---

    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private ProductCreateRequest createWithMedia() {
        return new ProductCreateRequest("iPhone 15", "iphone-15", "desc", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, MEDIA_ID,
            null, null, null, null);
    }

    @Test
    void create_mediaIdExists_acceptedAndPersisted() {
        ProductCreateRequest req = createWithMedia();
        Product product = sampleProduct();
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(mediaHeadClient.exists(MEDIA_ID)).thenReturn(true);
        when(mapper.toEntity(req)).thenReturn(product);
        when(repo.save(product)).thenReturn(product);
        when(mapper.toDetailResponse(product)).thenReturn(new ProductDetailResponse(ID, "iPhone 15",
            "iphone-15", "desc", "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, MEDIA_ID, null, null, null, null, null, null, null, null, null, null));

        service.create(req);

        verify(mediaHeadClient).exists(MEDIA_ID);
        verify(repo).save(product);
    }

    @Test
    void create_unknownMediaId_rejectedWithMed12004() {
        ProductCreateRequest req = createWithMedia();
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(mediaHeadClient.exists(MEDIA_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("MED-12004");
                assertThat(ex.getStatus().value()).isEqualTo(404);
            });
        verifyNoInteractions(publisher);
        verify(repo, never()).save(any());
    }

    @Test
    void create_mediaUnreachable_rejectedWithMed12006() {
        ProductCreateRequest req = createWithMedia();
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(mediaHeadClient.exists(MEDIA_ID))
            .thenThrow(BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE));

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("MED-12006");
                assertThat(ex.getStatus().value()).isEqualTo(503);
            });
        verifyNoInteractions(publisher);
        verify(repo, never()).save(any());
    }

    @Test
    void create_nullMediaId_skipsHeadCheck_keepsLegacyPath() {
        ProductCreateRequest req = sampleCreate();
        Product product = sampleProduct();
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(product);
        when(repo.save(product)).thenReturn(product);
        when(mapper.toDetailResponse(product)).thenReturn(new ProductDetailResponse(ID, "iPhone 15",
            "iphone-15", "desc", "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null, null, null, null, null, null, null));

        service.create(req);

        verifyNoInteractions(mediaHeadClient);
    }

    @Test
    void update_mediaIdExists_appliesReferenceAndPublishes() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, null,
            null, null, null, null, MEDIA_ID, null, null, null, null, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(mediaHeadClient.exists(MEDIA_ID)).thenReturn(true);
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(new ProductDetailResponse(ID, "iPhone 15",
            "iphone-15", null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, MEDIA_ID, null, null, null, null, null, null, null, null, null, null));

        service.update(ID, req);

        verify(mediaHeadClient).exists(MEDIA_ID);
        // mediaId wiring lives in mapper.partialUpdate (mocked here — its real
        // behavior is pinned in ProductMapperMediaFieldsTest); the gate must
        // pass BEFORE the update proceeds.
        verify(mapper).partialUpdate(existing, req);
        verify(publisher).publishUpdated(existing);
    }

    @Test
    void update_unknownMediaId_rejectedWithMed12004() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, null,
            null, null, null, null, MEDIA_ID, null, null, null, null, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(mediaHeadClient.exists(MEDIA_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.update(ID, req))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("MED-12004"));
        verifyNoInteractions(publisher);
    }

    @Test
    void update_nullMediaId_skipsHeadCheck() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, "new desc", null,
            new BigDecimal("1099.00"), null, null, null, null, null, null, null, null, false);
        ProductDetailResponse resp = new ProductDetailResponse(ID, "iPhone 15", "iphone-15",
            "new desc", "IP15-001", new BigDecimal("1099.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null, null, null, null, null, null, null);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(resp);

        service.update(ID, req);

        verifyNoInteractions(mediaHeadClient);
    }

    @Test
    void update_clearMediaId_skipsHeadCheckAndProceeds() {
        // H-2: an explicit clear carries no mediaId → the write-time gate must
        // not fire (nothing to verify) — the mapper removes the reference.
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, null,
            null, null, null, null, null, null, null, null, null, true);
        ProductDetailResponse resp = new ProductDetailResponse(ID, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null, null, null, null, null, null, null);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(resp);

        service.update(ID, req);

        verifyNoInteractions(mediaHeadClient);
        verify(mapper).partialUpdate(existing, req);
        verify(publisher).publishUpdated(existing);
    }
}