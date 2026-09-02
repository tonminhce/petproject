package com.shop.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Username + password login payload. H3 — Java record (fleet convention rule 1).
 */
public record LoginRequest(
        @NotBlank(message = "Username must not be blank")
        String username,

        @NotBlank(message = "Password must not be blank")
        String password
) {
}