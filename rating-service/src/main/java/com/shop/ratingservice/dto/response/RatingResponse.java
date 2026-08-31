package com.shop.ratingservice.dto.response;

import com.shop.ratingservice.entity.Rating;

import java.time.Instant;
import java.util.UUID;

public record RatingResponse(
        UUID id,
        UUID productId,
        UUID userId,
        int rating,
        String comment,
        boolean verified,
        boolean hidden,
        Instant editedAt,
        Instant createdAt
) {

    public static RatingResponse from(Rating rating) {
        return new RatingResponse(
                rating.getId(),
                rating.getProductId(),
                rating.getUserId(),
                rating.getRating(),
                rating.getComment(),
                rating.isVerified(),
                rating.isHidden(),
                rating.getEditedAt(),
                rating.getCreatedAt());
    }
}
