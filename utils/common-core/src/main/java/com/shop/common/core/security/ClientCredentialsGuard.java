package com.shop.common.core.security;

public final class ClientCredentialsGuard {

    private ClientCredentialsGuard() {
    }

    public static void requireConfigured(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(
                    "The client_credentials client-id must be configured");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "The client_credentials secret of client '" + clientId
                            + "' must be configured; refusing to start without it");
        }
    }
}
