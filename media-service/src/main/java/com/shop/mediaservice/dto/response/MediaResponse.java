package com.shop.mediaservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * D3 upload payload — {@code id, sha256, contentType, sizeBytes, variants[],
 * canonicalPath}. {@code duplicate} is true when the SHA-256 dedup resolved to
 * the EXISTING media (caller surfaces 200 + duplicate:true instead of 201).
 */
public record MediaResponse(
        UUID id,
        String sha256,
        String contentType,
        long sizeBytes,
        String canonicalPath,
        List<MediaVariantResponse> variants,
        boolean duplicate) {
}
