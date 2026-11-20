package com.shop.common.spring.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "shop.security.audience")
public record AudienceProperties(
        @DefaultValue("true") boolean enabled,
        String clientId,
        List<@NotBlank String> allowedClientIds,
        List<@NotBlank String> allowedAudiences,
        Map<String, List<@NotBlank String>> perServiceClientIds
) {

    public AudienceProperties {
        clientId = clientId == null ? "" : clientId.trim();
        allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
        allowedAudiences = allowedAudiences == null ? List.of() : List.copyOf(allowedAudiences);
        perServiceClientIds = perServiceClientIds == null ? Map.of() : Map.copyOf(perServiceClientIds);
    }

    public static AudienceProperties disabled() {
        return new AudienceProperties(false, "", List.of(), List.of(), Map.of());
    }

    public List<String> perServiceClientIdsFor(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return List.of();
        }
        return perServiceClientIds.getOrDefault(serviceId, List.of());
    }
}