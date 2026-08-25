package com.shop.common.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keycloak admin/client configuration bound from {@code shop.keycloak.*}.
 *
 * <p>No default credentials are set for the admin account — services that exercise
 * admin operations must supply {@code adminUsername} / {@code adminPassword} via
 * environment so we never ship hardcoded creds into a container.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "shop.keycloak")
public class KeycloakProperties {

    private String serverUrl = "http://localhost:8080";

    /**
     * Browser-facing Keycloak origin used to build the authorization redirect
     * (the user's browser must be able to resolve this host). Defaults to
     * {@link #serverUrl} when blank — override in clustered setups where the
     * backend reaches Keycloak via an internal service name but the browser uses
     * a public ingress host.
     */
    private String publicServerUrl;

    private String realm = "ecommerce";
    private String clientId = "shop-client";
    private String clientSecret;

    private String adminRealm = "master";
    private String adminClientId = "admin-cli";
    private String adminUsername;
    private String adminPassword;

    public String tokenEndpoint() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String logoutEndpoint() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/logout";
    }

    /** Browser-facing origin for the OIDC authorization redirect. */
    public String publicServerUrlOrDefault() {
        return (publicServerUrl == null || publicServerUrl.isBlank()) ? serverUrl : publicServerUrl;
    }

    /** Authorization endpoint the BROWSER is redirected to (login page). */
    public String authorizationEndpoint() {
        return publicServerUrlOrDefault() + "/realms/" + realm + "/protocol/openid-connect/auth";
    }

    /** Browser-facing end-session endpoint for RP-Initiated Logout. */
    public String endSessionEndpoint() {
        return publicServerUrlOrDefault() + "/realms/" + realm + "/protocol/openid-connect/logout";
    }

    /** Registration page (browser-facing) for self-service signup. */
    public String registrationEndpoint() {
        return publicServerUrlOrDefault() + "/realms/" + realm + "/protocol/openid-connect/registrations";
    }

    public String adminTokenEndpoint() {
        return serverUrl + "/realms/" + adminRealm + "/protocol/openid-connect/token";
    }

    public String adminUsersEndpoint() {
        return serverUrl + "/admin/realms/" + realm + "/users";
    }

    public String adminUserByIdEndpoint(String userId) {
        return adminUsersEndpoint() + "/" + userId;
    }

    public String adminRoleByNameEndpoint(String roleName) {
        return serverUrl + "/admin/realms/" + realm + "/roles/" + roleName;
    }

    public String adminUserRealmRoleMappingEndpoint(String userId) {
        return adminUserByIdEndpoint(userId) + "/role-mappings/realm";
    }

    /**
     * OIDC issuer URI for the configured realm — what Keycloak's
     * {@code /.well-known/openid-configuration} is rooted at and what the
     * resource server should pin its JWT decoder to.
     */
    public String issuerUri() {
        return stripTrailingSlash(serverUrl) + "/realms/" + realm;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
