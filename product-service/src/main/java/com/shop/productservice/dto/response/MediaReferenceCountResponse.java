package com.shop.productservice.dto.response;

import java.util.UUID;

/**
 * Wire payload of the H-4 internal reference-count endpoint
 * ({@code GET /internal/products/media-references/{mediaId}}, SERVICE-token
 * gated): how many LIVE product rows currently point at the given media.
 * media-service's purge-side {@code MediaReferenceClient} consumes this to
 * decide whether a soft-deleted media is still safe to hard-purge.
 *
 * @param mediaId        the media the products reference
 * @param referenceCount number of live products referencing it (0 = safe to purge)
 */
public record MediaReferenceCountResponse(UUID mediaId, long referenceCount) {
}
