package com.shop.authservice.dto.response;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Long expiresIn
) {
}