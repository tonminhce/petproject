package com.shop.authservice.dto.response;

import lombok.Builder;

import java.util.UUID;

/**
 * Read-side projection of the User aggregate. H3 — Java record (fleet
 * convention rule 1). Constructed by {@code UserServiceImpl} / {@code
 * UserController} — never returned by an entity accessor directly.
 * Lombok {@code @Builder} generates the canonical {@code builder()} for
 * tests that build partial responses.
 */
@Builder
public record UserResponse(
        UUID id,
        String fullname,
        String username,
        String email,
        String gender,
        String phone,
        String avatar
) {
}