package com.shop.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh-token exchange payload. H3 — Java record (fleet convention rule 1).
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken must not be blank")
        String refreshToken
) {
}