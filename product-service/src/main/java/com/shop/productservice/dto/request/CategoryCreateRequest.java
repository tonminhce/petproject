package com.shop.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CategoryCreateRequest(
    @NotBlank @Size(max = 100) String title,
    @NotBlank @Size(max = 100) String slug,
    @Size(max = 500)           String imageUrl,
    UUID parentId
) {}