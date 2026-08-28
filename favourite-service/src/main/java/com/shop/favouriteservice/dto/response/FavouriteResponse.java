package com.shop.favouriteservice.dto.response;

import java.time.Instant;
import java.util.UUID;

public record FavouriteResponse(
        UUID id,
        UUID userId,
        UUID productId,
        Instant createdAt
) {}
