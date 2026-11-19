package com.shop.authservice.dto.response;

import java.util.UUID;

/**
 * Read-side projection of the User aggregate. H3 — Java record (fleet
 * convention rule 1: layer + records). Constructed by {@code UserServiceImpl} /
 * {@code UserController} via the canonical record constructor. Never returned
 * by an entity accessor directly — the service layer maps the entity to this
 * DTO.
 */
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