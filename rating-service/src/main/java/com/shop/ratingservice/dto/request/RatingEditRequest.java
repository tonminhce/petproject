package com.shop.ratingservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RatingEditRequest(

        @NotNull
        @Min(1)
        @Max(5)
        Integer rating,

        @NotNull
        @Size(min = 5, max = 2000)
        String comment
) {}
