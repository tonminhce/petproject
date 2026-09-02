package com.shop.productservice.service.impls;

import com.shop.productservice.client.MediaHeadClient;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.entity.Product;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.AuditorAware;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H-2 explicit media clear — service level with the REAL mapper (the mocked
 * mapper in {@link ProductServiceImplTest} cannot prove persistence): sending
 * {@code clearMediaId=true} removes the stored {@code media_id} reference, the
 * derived image falls back to the legacy free-text {@code imageUrl} (spec D5),
 * the write-time HEAD gate never fires (nothing to verify on a clear), and the
 * ProductUpdated event still publishes so the search doc refreshes.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplMediaClearTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Mock ProductRepository repo;
    @Mock CategoryRepository categoryRepo;
    @Mock BrandRepository brandRepo;
    @Mock ProductEventPublisher publisher;
    @Mock AuditorAware<String> auditorAware;
    @Mock MediaHeadClient mediaHeadClient;
    @Mock org.springframework.cache.CacheManager cacheManager;

    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        // Real mapper + real ModelMapper — the partialUpdate branch is the unit under test here.
        service = new ProductServiceImpl(repo, categoryRepo, brandRepo,
            new ProductMapper(new ModelMapper()), publisher, auditorAware, mediaHeadClient, cacheManager);
    }

    private Product productWithMedia() {
        return Product.builder()
            .id(ID)
            .title("iPhone 15")
            .slug("iphone-15")
            .sku("IP15-001")
            .priceUnit(new BigDecimal("999.00"))
            .quantity(10)
            .status(ProductStatus.ACTIVE)
            .imageUrl("http://legacy.example/ip15.png")
            .mediaId(MEDIA_ID)
            .build();
    }

    @Test
    @DisplayName("clearMediaId=true → media_id cleared, legacy imageUrl fallback, no HEAD check")
    void update_clearMediaId_persistsNullWithoutHeadCheck() {
        Product existing = productWithMedia();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, null, null,
            null, null, null, "http://legacy.example/ip15.png", null, null, null, null, null, true);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        ProductDetailResponse result = service.update(ID, req);

        assertThat(result.mediaId()).as("explicit clear must persist a null media_id").isNull();
        assertThat(result.imageUrl()).as("derived image falls back to legacy imageUrl (spec D5)")
            .isEqualTo("http://legacy.example/ip15.png");
        verifyNoInteractions(mediaHeadClient);
        verify(publisher).publishUpdated(existing);
    }

    @Test
    @DisplayName("clearMediaId=true alongside other fields → only the reference is removed")
    void update_clearMediaId_alongsideOtherFields_leavesThemApplied() {
        Product existing = productWithMedia();
        ProductUpdateRequest req = new ProductUpdateRequest("iPhone 16", null, null, null,
            new BigDecimal("1099.00"), null, null, null, null, null, null, null, null, true);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        ProductDetailResponse result = service.update(ID, req);

        assertThat(result.title()).isEqualTo("iPhone 16");
        assertThat(result.priceUnit()).isEqualByComparingTo("1099.00");
        assertThat(result.mediaId()).isNull();
        verifyNoInteractions(mediaHeadClient);
    }

    @Test
    @DisplayName("flag absent + mediaId null → existing reference kept (no-op semantics unchanged)")
    void update_absentFlag_keepsExistingReference() {
        Product existing = productWithMedia();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, "new desc", null,
            null, null, null, null, null, null, null, null, null, false);
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        ProductDetailResponse result = service.update(ID, req);

        assertThat(result.mediaId()).as("absent flag must never clear").isEqualTo(MEDIA_ID);
        assertThat(result.imageUrl()).isEqualTo("/api/v1/medias/" + MEDIA_ID);
        verifyNoInteractions(mediaHeadClient);
    }
}
