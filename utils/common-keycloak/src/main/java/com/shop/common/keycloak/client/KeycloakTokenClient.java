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
