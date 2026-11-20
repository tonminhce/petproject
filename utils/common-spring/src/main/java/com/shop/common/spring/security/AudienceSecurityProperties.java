package com.shop.common.spring.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "shop.security")
public record AudienceSecurityProperties(
        @NotEmpty List<@NotBlank String> expectedClientIds
) {

    public AudienceSecurityProperties {
        expectedClientIds = expectedClientIds == null ? List.of() : List.copyOf(expectedClientIds);
    }
}
