package com.shop.authservice.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update payload for the authenticated user (or admin acting on a user).
 * All fields are optional; the service layer skips nulls. H3 — Java record
 * (fleet convention rule 1).
 */
public record UpdateUserRequest(
        @Size(min = 6, max = 50, message = "The fullName must be 6 characters or more")
        String fullName,

        @Size(max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}$",
                message = "Invalid email format")
        String email,

        String gender,

        @Size(min = 10, max = 12, message = "Phone number must be between 10 and 12 digits")
        @Pattern(regexp = "^\\+84[0-9]{9,10}$|^0[0-9]{9,10}$", message = "The phone number is not in the correct format")
        String phone,

        @Pattern(regexp = "^(https?://)\\S+$", message = "Avatar URL must be a valid HTTP or HTTPS URL")
        String avatar
) {
}