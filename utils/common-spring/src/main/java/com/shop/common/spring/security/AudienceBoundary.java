package com.shop.common.spring.security;

import java.util.ArrayList;
import java.util.List;

public record AudienceBoundary(
        List<String> allowedClientIds,
        List<String> allowedAudiences
) {

    public AudienceBoundary {
        allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
        allowedAudiences = allowedAudiences == null ? List.of() : List.copyOf(allowedAudiences);
    }

    public static AudienceBoundary resolve(AudienceProperties audience,
                                           String configuredClientIds,
                                           String configuredAudiences) {
        List<String> clientIds = resolveClientIds(audience, configuredClientIds);
        List<String> audiences = audience.allowedAudiences().isEmpty()
                ? split(configuredAudiences)
                : audience.allowedAudiences();
        return new AudienceBoundary(clientIds, audiences);
    }

    public static List<String> resolveClientIds(AudienceProperties audience, String configured) {
        if (configured != null && !configured.isBlank() && !configured.equals(AudienceBoundaryEnvironmentCustomiser.SHARED_DEFAULT_CLIENT_IDS)) {
            return split(configured);
        }
        if (!audience.allowedClientIds().isEmpty()) {
            return audience.allowedClientIds();
        }
        List<String> perService = audience.perServiceClientIdsFor(audience.clientId());
        if (!perService.isEmpty()) {
            return perService;
        }
        return split(configured);
    }

    public boolean allowsClient(String clientId) {
        if (allowedClientIds.isEmpty()) {
            return false;
        }
        return clientId != null && allowedClientIds.contains(clientId);
    }

    public boolean allowsAnyClient() {
        return !allowedClientIds.isEmpty();
    }

    public boolean allowsAudience(List<String> audiences) {
        if (allowedAudiences.isEmpty()) {
            return true;
        }
        if (audiences == null || audiences.isEmpty()) {
            return false;
        }
        return audiences.stream().anyMatch(allowedAudiences::contains);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return List.copyOf(parts);
    }
}
