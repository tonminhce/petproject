package com.shop.common.keycloak.client;

import com.shop.common.keycloak.dto.KeycloakTokenResponse;
import com.shop.common.keycloak.exception.KeycloakClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.*;

/**
 * Client for Keycloak token operations (login, refresh, logout, authorization code exchange).
 * Uses client credentials (client_id + client_secret) for authentication.
 */
public class KeycloakTokenClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenClient.class);
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String AUTHORIZATION_CODE = "authorization_code";

    private final RestClient restClient;
    private final String tokenEndpoint;
    private final String logoutEndpoint;
    private final String clientId;
    private final String clientSecret;

    public KeycloakTokenClient(RestClient.Builder restClientBuilder,
                               String tokenEndpoint,
                               String logoutEndpoint,
                               String clientId,
                               String clientSecret) {
        this.restClient = restClientBuilder.build();
        this.tokenEndpoint = tokenEndpoint;
        this.logoutEndpoint = logoutEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Authenticate user with username and password (Resource Owner Password Credentials grant).
     *
     * @param username user's username
     * @param password user's password
     * @return token response containing access_token, refresh_token, etc.
     * @throws KeycloakClientException if authentication fails
     */
    public KeycloakTokenResponse login(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, PASSWORD);
        form.add(USERNAME, username);
        form.add(PASSWORD, password);
        form.add(CLIENT_ID, clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            form.add(CLIENT_SECRET, clientSecret);
        }

        return postToken(form);
    }

    /**
     * Refresh an expired access token using a refresh token.
     *
     * @param refreshToken the refresh token
     * @return new token response with fresh access_token
     * @throws KeycloakClientException if refresh fails
     */
    public KeycloakTokenResponse refreshToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, REFRESH_TOKEN);
        form.add(REFRESH_TOKEN, refreshToken);
        form.add(CLIENT_ID, clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            form.add(CLIENT_SECRET, clientSecret);
        }

        return postToken(form);
    }

    /**
     * Exchange authorization code for tokens (Authorization Code grant).
     * Used in browser-based SSO flows.
     *
     * @param code authorization code from Keycloak callback
     * @param redirectUri must match the redirect_uri used in authorization request
     * @return token response
     * @throws KeycloakClientException if exchange fails
     */
    public KeycloakTokenResponse exchangeAuthorizationCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, AUTHORIZATION_CODE);
        form.add(CODE, code);
        form.add(REDIRECT_URI, redirectUri);
        form.add(CLIENT_ID, clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            form.add(CLIENT_SECRET, clientSecret);
        }

        return postToken(form);
    }

    /**
     * Logout user by revoking refresh token.
     *
     * @param refreshToken the refresh token to revoke
     * @throws KeycloakClientException if logout fails
     */
    public void logout(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(REFRESH_TOKEN, refreshToken);
        form.add(CLIENT_ID, clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            form.add(CLIENT_SECRET, clientSecret);
        }

        try {
            restClient.post()
                    .uri(logoutEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak logout failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Logout failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }

    /**
     * Verify user credentials by attempting to login.
     * Returns true if credentials are valid, false otherwise.
     *
     * @param username user's username
     * @param password user's password
     * @return true if credentials are valid
     */
    public boolean verifyCredentials(String username, String password) {
        try {
            KeycloakTokenResponse response = login(username, password);
            if (response != null && response.refreshToken() != null) {
                try {
                    logout(response.refreshToken());
                } catch (Exception ex) {
                    log.warn("Failed to logout temporary session during credential verification: {}", ex.getMessage());
                }
            }
            return true;
        } catch (KeycloakClientException e) {
            return false;
        }
    }

    private KeycloakTokenResponse postToken(MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KeycloakTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error("Keycloak token request failed: {}", e.getResponseBodyAsString());
            throw new KeycloakClientException(
                    "Token request failed: " + e.getResponseBodyAsString(),
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    e
            );
        }
    }
}
