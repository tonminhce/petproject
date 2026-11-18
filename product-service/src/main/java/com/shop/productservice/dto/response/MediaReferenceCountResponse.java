package com.shop.productservice.dto.response;

import java.util.UUID;

public record MediaReferenceCountResponse(UUID mediaId, long referenceCount) {
}
