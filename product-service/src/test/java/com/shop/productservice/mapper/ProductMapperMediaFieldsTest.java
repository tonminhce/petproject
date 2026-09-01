package com.shop.productservice.mapper;

import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Media-reference mapping (media epic spec D5): the displayed image is
 * DERIVED at mapping time — mediaId present → canonicalPath
 * {@code /api/v1/medias/{id}}; mediaId null → legacy free-text imageUrl
 * (backward compat for legacy rows). mediaId itself is carried on both
 * payloads. Nothing is stored on the entity — the canonical path is computed.
 */
class ProductMapperMediaFieldsTest {

    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private ProductMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ProductMapper(new ModelMapper());
    }

    private Product product() {
        return Product.builder()
            .title("iPhone 15")
            .slug("iphone-15")
            .sku("IP15-001")
            .priceUnit(new BigDecimal("999.00"))
            .quantity(10)
            .status(ProductStatus.ACTIVE)
            .build();
    }

    @Test
    @DisplayName("detail: mediaId present → imageUrl derived to canonicalPath, mediaId carried")
    void detailResponse_mediaIdPresent_derivesCanonicalPath() {
        Product p = product();
        p.setMediaId(MEDIA_ID);
        p.setImageUrl("http://legacy.example/ip15.png");

        ProductDetailResponse resp = mapper.toDetailResponse(p);

        assertThat(resp.mediaId()).isEqualTo(MEDIA_ID);
        assertThat(resp.imageUrl()).isEqualTo("/api/v1/medias/" + MEDIA_ID);
    }

    @Test
    @DisplayName("detail: mediaId null → legacy free-text imageUrl (backward compat)")
    void detailResponse_mediaIdNull_fallsBackToLegacyImageUrl() {
        Product p = product();
        p.setImageUrl("http://legacy.example/ip15.png");

        ProductDetailResponse resp = mapper.toDetailResponse(p);

        assertThat(resp.mediaId()).isNull();
        assertThat(resp.imageUrl()).isEqualTo("http://legacy.example/ip15.png");
    }

    @Test
    @DisplayName("summary: mediaId present → imageUrl derived to canonicalPath, mediaId carried")
    void summaryResponse_mediaIdPresent_derivesCanonicalPath() {
        Product p = product();
        p.setMediaId(MEDIA_ID);
        p.setImageUrl("http://legacy.example/ip15.png");

        ProductSummaryResponse resp = mapper.toSummaryResponse(p);

        assertThat(resp.mediaId()).isEqualTo(MEDIA_ID);
        assertThat(resp.imageUrl()).isEqualTo("/api/v1/medias/" + MEDIA_ID);
    }

    @Test
    @DisplayName("summary: mediaId null → legacy free-text imageUrl (backward compat)")
    void summaryResponse_mediaIdNull_fallsBackToLegacyImageUrl() {
        Product p = product();
        p.setImageUrl("http://legacy.example/ip15.png");

        ProductSummaryResponse resp = mapper.toSummaryResponse(p);

        assertThat(resp.mediaId()).isNull();
        assertThat(resp.imageUrl()).isEqualTo("http://legacy.example/ip15.png");
    }

    @Test
    @DisplayName("both null → both image outputs null (no NPE)")
    void bothImageSourcesNull_outputsNull() {
        Product p = product();

        ProductDetailResponse detail = mapper.toDetailResponse(p);
        ProductSummaryResponse summary = mapper.toSummaryResponse(p);

        assertThat(detail.imageUrl()).isNull();
        assertThat(summary.imageUrl()).isNull();
    }

    // --- H-2: explicit clearMediaId on partialUpdate ---

    @Test
    @DisplayName("partialUpdate: clearMediaId=true → media_id removed even when the target referenced one")
    void partialUpdate_clearMediaId_true_removesReference() {
        Product p = product();
        p.setMediaId(MEDIA_ID);
        p.setImageUrl("http://legacy.example/ip15.png");

        mapper.partialUpdate(p, update(null, true));

        assertThat(p.getMediaId()).as("explicit clear must remove the reference").isNull();
        assertThat(p.getTitle()).as("clear must not leak into other fields").isEqualTo("iPhone 15");
    }

    @Test
    @DisplayName("partialUpdate: flag absent (false) + mediaId present → reference replaced (unchanged semantics)")
    void partialUpdate_flagAbsent_mediaIdReplaces() {
        Product p = product();

        mapper.partialUpdate(p, update(MEDIA_ID, false));

        assertThat(p.getMediaId()).isEqualTo(MEDIA_ID);
    }

    @Test
    @DisplayName("partialUpdate: flag absent + mediaId null → reference untouched (original null-guard)")
    void partialUpdate_flagAbsent_nullMediaId_isNoOp() {
        Product p = product();
        p.setMediaId(MEDIA_ID);

        mapper.partialUpdate(p, update(null, false));

        assertThat(p.getMediaId()).isEqualTo(MEDIA_ID);
    }

    @Test
    @DisplayName("partialUpdate: clear=true overrides a concurrently set media_id field on the target")
    void partialUpdate_clearWinsOverExistingReference() {
        Product p = product();
        p.setMediaId(MEDIA_ID);
        p.setTitle("Renamed");

        mapper.partialUpdate(p, new ProductUpdateRequest("Renamed again", null, null, null,
            null, null, null, null, null, null, null, null, null, true));

        assertThat(p.getMediaId()).isNull();
        assertThat(p.getTitle()).isEqualTo("Renamed again");
    }

    private ProductUpdateRequest update(UUID mediaId, boolean clearMediaId) {
        return new ProductUpdateRequest(null, null, null, null, null, null, null, null,
            mediaId, null, null, null, null, clearMediaId);
    }
}
