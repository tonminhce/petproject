package com.shop.common.spring.security;

import java.util.List;

public record AudienceBoundary(
        List<String> allowedClientIds,
        List<String> allowedAudiences
) {

    public AudienceBoundary {
        allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
        allowedAudiences = allowedAudiences == null ? List.of() : List.copyOf(allowedAudiences);
    }

    public boolean allowsClient(String clientId) {
        return clientId != null && allowedClientIds.contains(clientId);
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
}
