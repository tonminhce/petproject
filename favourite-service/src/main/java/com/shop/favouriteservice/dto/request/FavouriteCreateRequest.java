package com.shop.favouriteservice.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FavouriteCreateRequest(
        @NotNull(message = "productId must not be null")
        UUID productId
) {}
