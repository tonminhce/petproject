package com.shop.authservice.dto.response;

import java.util.UUID;

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