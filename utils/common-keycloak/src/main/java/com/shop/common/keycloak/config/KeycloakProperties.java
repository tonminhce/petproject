package com.shop.common.keycloak.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Keycloak integration.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "shop.keycloak")
public class KeycloakProperties {

    /**
     * Keycloak server URL (internal, for service-to-service communication).
     */
    private String serverUrl = "http://localhost:8080";

    /**
     * Keycloak public URL (for browser redirects, e.g., login page).
     */
    private String publicServerUrl;

    /**
     * Target realm for user operations.
     */
    private String realm = "ecommerce";

    /**
     * OAuth2 client ID for token operations.
     */
    private String clientId = "shop-backend";

    /**
     * OAuth2 client secret (optional, for confidential clients).
     */
    private String clientSecret;

    /**
     * Admin realm (usually "master").
     */
    private String adminRealm = "master";

    /**
     * Admin client ID (usually "admin-cli").
     */
    private String adminClientId = "admin-cli";

    /**
     * Admin username for user management operations.
     */
    private String adminUsername;

    /**
     * Admin password for user management operations.
     */
    private String adminPassword;

    public String getPublicServerUrlOrDefault() {
        return (publicServerUrl == null || publicServerUrl.isBlank()) ? serverUrl : publicServerUrl;
    }

    public String tokenEndpoint() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String logoutEndpoint() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/logout";
    }

    public String authorizationEndpoint() {
        return getPublicServerUrlOrDefault() + "/realms/" + realm + "/protocol/openid-connect/auth";
    }

    public String endSessionEndpoint() {
        return getPublicServerUrlOrDefault() + "/realms/" + realm + "/protocol/openid-connect/logout";
    }

    public String registrationEndpoint() {
        return getPublicServerUrlOrDefault() + "/realms/" + realm + "/protocol/openid-connect/registrations";
    }

    public String adminTokenEndpoint() {
        return serverUrl + "/realms/" + adminRealm + "/protocol/openid-connect/token";
    }

    public String usersEndpoint() {
        return serverUrl + "/admin/realms/" + realm + "/users";
    }

    public String rolesEndpoint() {
        return serverUrl + "/admin/realms/" + realm + "/roles";
    }

    public String issuerUri() {
        return serverUrl + "/realms/" + realm;
    }
}
