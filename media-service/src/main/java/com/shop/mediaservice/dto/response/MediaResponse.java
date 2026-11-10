package com.shop.mediaservice.dto.response;

import java.util.List;
import java.util.UUID;

public record MediaResponse(
        UUID id,
        String sha256,
        String contentType,
        long sizeBytes,
        String canonicalPath,
        List<MediaVariantResponse> variants,
        boolean duplicate) {
}
