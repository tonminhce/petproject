package com.shop.common.keycloak.client;

import com.shop.common.keycloak.dto.KeycloakTokenResponse;
import com.shop.common.keycloak.exception.KeycloakClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.*;

public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String CLIENT_CREDENTIALS = "client_credentials";
    private static final String BEARER = "Bearer ";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String adminTokenEndpoint;
    private final String usersEndpoint;
    private final String rolesEndpoint;
    private final String clientsEndpoint;
    private final String adminClientId;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminClientSecret;
    private final String serviceAccountTokenEndpoint;

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               String adminTokenEndpoint,
                               String usersEndpoint,
                               String rolesEndpoint,
                               String adminClientId,
                               String adminUsername,
                               String adminPassword) {
        this(restClientBuilder, adminTokenEndpoint, usersEndpoint, rolesEndpoint, adminClientId,
                adminUsername, adminPassword, null, null, null);
    }

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               String adminTokenEndpoint,
                               String usersEndpoint,
                               String rolesEndpoint,
                               String adminClientId,
                               String adminUsername,
                               String adminPassword,
                               String adminClientSecret,
                               String serviceAccountTokenEndpoint) {
        this(restClientBuilder, adminTokenEndpoint, usersEndpoint, rolesEndpoint, adminClientId,
                adminUsername, adminPassword, adminClientSecret, serviceAccountTokenEndpoint, null);
    }

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               String adminTokenEndpoint,
                               String usersEndpoint,
                               String rolesEndpoint,
                               String adminClientId,
                               String adminUsername,
                               String adminPassword,
                               String adminClientSecret,
                               String serviceAccountTokenEndpoint,
                               String clientsEndpoint) {
        this.restClient = restClientBuilder.build();
        this.adminTokenEndpoint = adminTokenEndpoint;
        this.usersEndpoint = usersEndpoint;
        this.rolesEndpoint = rolesEndpoint;
        this.clientsEndpoint = clientsEndpoint;
        this.adminClientId = adminClientId;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.adminClientSecret = adminClientSecret;
        this.serviceAccountTokenEndpoint = serviceAccountTokenEndpoint;
    }

    public boolean hasServiceAccountCredentials() {
        return adminClientSecret != null && !adminClientSecret.isBlank()
                && serviceAccountTokenEndpoint != null && !serviceAccountTokenEndpoint.isBlank();
    }

    private String getServiceAccountAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, CLIENT_CREDENTIALS);
        form.add(CLIENT_ID, adminClientId);
        form.add(CLIENT_SECRET, adminClientSecret);

        try {
            KeycloakTokenResponse response = restClient.post()
                    .uri(serviceAccountTokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KeycloakTokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new KeycloakClientException("Failed to obtain the admin service-account token",
                        HttpStatus.UNAUTHORIZED);
            }

            long ttl = (response.expiresIn() != null && response.expiresIn() > 0) ? response.expiresIn() : 300L;
            cachedToken.set(new CachedAdminToken(response.accessToken(), Instant.now().plusSeconds(ttl)));

            return response.accessToken();
        } catch (RestClientResponseException e) {
            log.error("Keycloak service-account token request failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Service-account token request failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    public boolean hasAdminCredentials() {
        return adminUsername != null && !adminUsername.isBlank()
                && adminPassword != null && !adminPassword.isBlank();
    }

    public String createUser(String username, String email, String fullName,
                             String password, List<String> realmRoles) {
        String adminToken = getAdminAccessToken();

        Map<String, Object> userPayload = buildUserPayload(username, email, fullName, password);

        URI location;
        try {
            location = restClient.post()
                    .uri(usersEndpoint)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(userPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
        } catch (RestClientResponseException e) {
            log.error("Keycloak create user failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Create user failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }

        if (location == null) {
            throw new KeycloakClientException("Create user returned no location header", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String userId = extractUserIdFromLocation(location);

        if (realmRoles != null && !realmRoles.isEmpty()) {
            assignRealmRoles(adminToken, userId, realmRoles);
        }

        return userId;
    }

    public void deleteUser(String userId) {
        String adminToken = getAdminAccessToken();

        try {
            restClient.delete()
                    .uri(usersEndpoint + "/" + userId)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak delete user failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Delete user failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    public void disableUser(String userId) {
        updateUserEnabled(userId, false);
    }

    public void enableUser(String userId) {
        updateUserEnabled(userId, true);
    }

    private void updateUserEnabled(String userId, boolean enabled) {
        String adminToken = getAdminAccessToken();
        Map<String, Object> payload = Map.of("enabled", enabled);
        try {
            restClient.put().uri(usersEndpoint + "/" + userId)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).body(payload)
                    .retrieve().toBodilessEntity();
            if (!enabled) {
                restClient.post().uri(usersEndpoint + "/" + userId + "/logout")
                        .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                        .retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            log.error("Keycloak user state update failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException("Update user state failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()), e);
        }
    }

    public void logoutUser(String userId) {
        String adminToken = getAdminAccessToken();
        try {
            restClient.post()
                    .uri(usersEndpoint + "/" + userId + "/logout")
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak session revocation failed for user {}: {}", userId, e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Session revocation failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    public boolean isDirectAccessGrantEnabled(String clientId) {
        String adminToken = getAdminAccessToken();
        try {
            List<Map<String, Object>> clients = restClient.get()
                    .uri(clientsEndpoint + "?clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .retrieve()
                    .body(LIST_TYPE);

            if (clients == null || clients.isEmpty()) {
                throw new KeycloakClientException(
                        "Keycloak client not found: " + clientId, HttpStatus.NOT_FOUND);
            }

            return Boolean.TRUE.equals(clients.get(0).get("directAccessGrantsEnabled"));
        } catch (RestClientResponseException e) {
            log.warn("Could not read the grant configuration of Keycloak client {}: {}",
                    clientId, e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Could not read the client grant configuration: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    public void resetUserPassword(String userId, String newPassword, boolean temporary) {
        String adminToken = getAdminAccessToken();

        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", newPassword);
        credential.put("temporary", temporary);

        try {
            restClient.put()
                    .uri(usersEndpoint + "/" + userId + "/reset-password")
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak reset password failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Reset password failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    private static final long SKEW_SECONDS = 30L;
    private final AtomicReference<CachedAdminToken> cachedToken = new AtomicReference<>();

    private record CachedAdminToken(String token, Instant expiresAt) {}

    private String getAdminAccessToken() {
        CachedAdminToken current = cachedToken.get();
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(SKEW_SECONDS))) {
            return current.token();
        }

        synchronized (this) {
            current = cachedToken.get();
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(SKEW_SECONDS))) {
                return current.token();
            }

            if (hasServiceAccountCredentials()) {
                return getServiceAccountAccessToken();
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add(GRANT_TYPE, PASSWORD);
            form.add(CLIENT_ID, adminClientId);
            form.add(USERNAME, adminUsername);
            form.add(PASSWORD, adminPassword);

            try {
                KeycloakTokenResponse response = restClient.post()
                        .uri(adminTokenEndpoint)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(KeycloakTokenResponse.class);

                if (response == null || response.accessToken() == null) {
                    throw new KeycloakClientException("Failed to obtain admin access token", HttpStatus.UNAUTHORIZED);
                }

                long ttl = (response.expiresIn() != null && response.expiresIn() > 0) ? response.expiresIn() : 300L;
                cachedToken.set(new CachedAdminToken(response.accessToken(), Instant.now().plusSeconds(ttl)));

                return response.accessToken();
            } catch (RestClientResponseException e) {
                log.error("Keycloak admin token request failed: {}", e.getResponseBodyAsString());
                throw new KeycloakClientException(
                        "Admin token request failed: " + e.getResponseBodyAsString(),
                        HttpStatus.valueOf(e.getStatusCode().value()),
                        e
                );
            }
        }
    }

    public void assignRealmRole(String userId, String roleName) {
        String adminToken = getAdminAccessToken();
        Map<String, Object> role = fetchRealmRole(adminToken, roleName);

        try {
            restClient.post()
                    .uri(usersEndpoint + "/" + userId + "/role-mappings/realm")
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak assign role failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Assign role failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    public void removeRealmRole(String userId, String roleName) {
        String adminToken = getAdminAccessToken();
        Map<String, Object> role = fetchRealmRole(adminToken, roleName);

        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(usersEndpoint + "/" + userId + "/role-mappings/realm")
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak remove role failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Remove role failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    private Map<String, Object> fetchRealmRole(String adminToken, String roleName) {
        try {
            Map<String, Object> role = restClient.get()
                    .uri(rolesEndpoint + "/" + roleName)
                    .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                    .retrieve()
                    .body(MAP_TYPE);

            if (role == null) {
                throw new KeycloakClientException("Realm role not found: " + roleName, HttpStatus.NOT_FOUND);
            }
            return role;
        } catch (RestClientResponseException e) {
            log.warn("Failed to fetch role {}: {}", roleName, e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Realm role lookup failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    private void assignRealmRoles(String adminToken, String userId, List<String> roleNames) {
        List<Map<String, Object>> roleRepresentations = new ArrayList<>();

        for (String roleName : roleNames) {
            roleRepresentations.add(fetchRealmRole(adminToken, roleName));
        }

        if (!roleRepresentations.isEmpty()) {
            try {
                restClient.post()
                        .uri(usersEndpoint + "/" + userId + "/role-mappings/realm")
                        .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(roleRepresentations)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException e) {
                log.error("Keycloak assign roles failed: {}", e.getResponseBodyAsString());
                throw new KeycloakClientException(
                        "Assign roles failed: " + e.getResponseBodyAsString(),
                        HttpStatus.valueOf(e.getStatusCode().value()),
                        e
                );
            }
        }
    }

    private Map<String, Object> buildUserPayload(String username, String email,
                                                  String fullName, String password) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", email);
        payload.put("enabled", true);
        payload.put("emailVerified", true);

        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", password);
        credential.put("temporary", false);
        payload.put("credentials", List.of(credential));

        if (fullName != null && !fullName.trim().isEmpty()) {
            String[] parts = fullName.trim().split("\\s+", 2);
            payload.put("firstName", parts[0]);
            if (parts.length > 1) {
                payload.put("lastName", parts[1]);
            }
        }

        return payload;
    }

    private String extractUserIdFromLocation(URI location) {
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
