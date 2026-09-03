package com.shop.authservice.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AddressResponse(
        UUID id,
        UUID userId,
        String recipientName,
        String phoneNumber,
        String province,
        String district,
        String ward,
        String detailAddress,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) {
}
