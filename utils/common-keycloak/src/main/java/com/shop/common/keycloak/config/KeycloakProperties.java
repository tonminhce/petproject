package com.shop.common.keycloak.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    private String realm = "ecommerce";

    private String clientId = "shop-backend";

    private String clientSecret;

    private String adminRealm = "master";

    private String adminClientId = "admin-cli";

    private String adminUsername;

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
