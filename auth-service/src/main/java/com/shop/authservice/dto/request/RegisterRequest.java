package com.shop.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class RegisterRequest {
    @NotBlank(message = "The fullName must not be left blank")
    @Size(min = 6, max = 50, message = "The fullName must be 6 characters or more")
    private String fullName;

    @NotBlank(message = "The username must not be left blank")
    @Size(min = 6, max = 50, message = "The username must be 6 characters or more")
    private String username;

    @NotBlank(message = "The password must not be left blank")
    @Size(min = 8, max = 50, message = "Password must be between 8 and 50 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d]{8,}$",
            message = "Password must contain uppercase, lowercase letters and numbers")
    private String password;

    @Size(max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}$",
            message = "Invalid email format")
    private String email;

    @NotBlank(message = "Gender must not be left blank")
    private String gender;

    @Size(min = 10, max = 12, message = "Phone number must be between 10 and 12 digits")
    @Pattern(regexp = "^\\+84[0-9]{9,10}$|^0[0-9]{9,10}$", message = "The phone number is not in the correct format")
    private String phone;

    @Pattern(regexp = "^(https?://)\\S+$", message = "Avatar URL must be a valid HTTP or HTTPS URL")
    private String avatar;

    /**
     * C1 — caller-supplied role names. The server enforces a server-side whitelist
     * (see {@code UserServiceImpl.SELF_REGISTRATION_ALLOWED_ROLES}); values not on
     * the whitelist are silently dropped. ADMIN/SERVICE/MANAGER cannot be obtained
     * through this endpoint by design — keep the field here so existing clients
     * don't break, but don't trust it.
     */
    @Deprecated
    private Set<String> roles;
}
