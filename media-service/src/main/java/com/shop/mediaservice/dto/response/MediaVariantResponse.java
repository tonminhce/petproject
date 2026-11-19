package com.shop.mediaservice.dto.response;

/**
 * One stored variant render (D2) — variant/format/width mirror the
 * {@code media_variants} row.
 */
public record MediaVariantResponse(
        String variant,
        String format,
        int width,
        long bytes,
        String objectKey) {
}
