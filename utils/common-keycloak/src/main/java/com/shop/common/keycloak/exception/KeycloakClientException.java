package com.shop.common.keycloak.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when Keycloak API calls fail.
 * Carries HTTP status for proper error response mapping.
 */
public class KeycloakClientException extends RuntimeException {

    private final HttpStatus status;

    public KeycloakClientException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public KeycloakClientException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
