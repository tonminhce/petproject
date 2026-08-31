package com.shop.ratingservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RatingHideRequest(

        @NotBlank
        @Size(max = 500)
        String reason
) {}
