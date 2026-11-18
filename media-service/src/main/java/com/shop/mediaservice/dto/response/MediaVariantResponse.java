package com.shop.mediaservice.dto.response;

public record MediaVariantResponse(
        String variant,
        String format,
        int width,
        long bytes,
        String objectKey) {
}
