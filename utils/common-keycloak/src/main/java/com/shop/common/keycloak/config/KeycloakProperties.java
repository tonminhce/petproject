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

    /**
     * The public client of the realm.
     *
     * <p>The default must name a client that actually exists in the
     * provisioned realm ({@code docker/keycloak/import/ecommerce-realm.json}),
     * otherwise the posture check measures a client that was never
     * provisioned (F-079).</p>
     */
    private String clientId = "ecommerce-client";

    private String clientSecret;

    private String adminRealm = "master";

    private String adminClientId = "admin-cli";

    private String adminUsername;

    private String adminPassword;

    /**
     * The secret of the admin service-account client (F-078).
     *
     * <p>When it is configured, the admin token is fetched with the
     * {@code client_credentials} grant instead of the password grant: no
     * master-realm administrator credential travels through a plain password
     * grant on every admin call. The service account lives in the APPLICATION
     * realm, so its management permissions are scoped to that realm instead
     * of the whole Keycloak installation.</p>
     */
    private String adminClientSecret;

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

    /**
     * The token endpoint of the service-account grant (F-078). The service
     * account is a client of the APPLICATION realm, so its token is issued
     * there - not in the master realm.
     */
    public String serviceAccountTokenEndpoint() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String usersEndpoint() {
        return serverUrl + "/admin/realms/" + realm + "/users";
    }

    public String rolesEndpoint() {
        return serverUrl + "/admin/realms/" + realm + "/roles";
    }

    /**
     * The client endpoint of the realm (F-079).
     *
     * <p>The grant configuration of a client (the password / ROPC grant) is
     * a property of the CLIENT. Reading it through the users endpoint
     * answered for a user and reported the posture of a client that does not
     * exist.</p>
     */
    public String clientsEndpoint() {
        return serverUrl + "/admin/realms/" + realm + "/clients";
    }

    public String issuerUri() {
        return serverUrl + "/realms/" + realm;
    }
}
