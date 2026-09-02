package com.shop.common.keycloak.client;

import com.shop.common.keycloak.dto.KeycloakTokenResponse;
import com.shop.common.keycloak.exception.KeycloakClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.*;

/**
 * Client for Keycloak admin operations (user management, role assignment).
 * Uses admin credentials (admin username + password) to obtain admin access token.
 */
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String BEARER = "Bearer ";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String adminTokenEndpoint;
    private final String usersEndpoint;
    private final String rolesEndpoint;
    private final String adminClientId;
    private final String adminUsername;
    private final String adminPassword;

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               String adminTokenEndpoint,
                               String usersEndpoint,
                               String rolesEndpoint,
                               String adminClientId,
                               String adminUsername,
                               String adminPassword) {
        this.restClient = restClientBuilder.build();
        this.adminTokenEndpoint = adminTokenEndpoint;
        this.usersEndpoint = usersEndpoint;
        this.rolesEndpoint = rolesEndpoint;
        this.adminClientId = adminClientId;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    /**
     * Create a new user in Keycloak and assign realm roles.
     *
     * @param username user's username
     * @param email user's email
     * @param fullName user's full name (will be split into firstName + lastName)
     * @param password user's password
     * @param realmRoles list of realm role names to assign
     * @return Keycloak user ID
     * @throws KeycloakClientException if creation fails
     */
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

    /**
     * Delete a user from Keycloak.
     *
     * @param userId Keycloak user ID
     * @throws KeycloakClientException if deletion fails
     */
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

    /** Disable a user and invalidate active sessions in Keycloak. */
    public void disableUser(String userId) {
        updateUserEnabled(userId, false);
    }

    /** Re-enable a previously disabled user in Keycloak. */
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

    /**
     * Reset user's password in Keycloak.
     *
     * @param userId Keycloak user ID
     * @param newPassword new password to set
     * @param temporary if true, user must change password on next login
     * @throws KeycloakClientException if reset fails
     */
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

    private String getAdminAccessToken() {
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

    private void assignRealmRoles(String adminToken, String userId, List<String> roleNames) {
        List<Map<String, Object>> roleRepresentations = new ArrayList<>();

        for (String roleName : roleNames) {
            try {
                Map<String, Object> role = restClient.get()
                        .uri(rolesEndpoint + "/" + roleName)
                        .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
                        .retrieve()
                        .body(MAP_TYPE);

                if (role != null) {
                    roleRepresentations.add(role);
                }
            } catch (RestClientResponseException e) {
                log.warn("Failed to fetch role {}: {}", roleName, e.getResponseBodyAsString());
            }
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
