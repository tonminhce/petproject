package com.shop.productservice.dto.request;

import com.shop.productservice.constant.ProductStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Partial-update payload (ruling H-2 media semantics): every component is
 * optional — a {@code null}/absent field keeps the current value. Clearing the
 * media reference is EXPLICIT ({@code clearMediaId=true}), because a record
 * cannot distinguish "absent" from "null" for {@code mediaId} itself:
 * flag absent/false = reference untouched; flag true + {@code mediaId} null =
 * reference cleared. Flag and {@code mediaId} together are a binding-time 400
 * ({@code product.media.clear.conflict}, i18n EN+VI) — see
 * {@link #isMediaClearConsistent()}.
 */
public record ProductUpdateRequest(
    @Size(max = 200)             String title,
    @Size(max = 200)             String slug,
    @Size(max = 2000)            String description,
    @Size(max = 50)              String sku,
    @DecimalMin("0.0")           BigDecimal priceUnit,
    @Min(0)                      Integer quantity,
    ProductStatus status,
    @Size(max = 500)             String imageUrl,
    UUID mediaId,
    @DecimalMin("0.0")           BigDecimal weight,
    @Size(max = 50)              String dimensions,
    UUID categoryId,
    UUID brandId,
    Boolean clearMediaId
) {

    /**
     * H-2 cross-field guard: {@code clearMediaId=true} is only meaningful as a
     * REMOVE — it cannot simultaneously carry a replacement {@code mediaId}.
     * Surfaced as a property constraint so {@code @Valid} on the controller
     * rejects the conflicting body with the common 400 validation channel
     * (ERR-0422-V); the message key resolves from the platform i18n bundles.
     */
    @AssertTrue(message = "{product.media.clear.conflict}")
    boolean isMediaClearConsistent() {
        return !Boolean.TRUE.equals(clearMediaId) || mediaId == null;
    }
}
