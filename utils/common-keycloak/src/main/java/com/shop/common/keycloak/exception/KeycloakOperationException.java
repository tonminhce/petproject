package com.shop.common.keycloak.exception;

import org.springframework.http.HttpStatus;

import java.util.Optional;

/**
 * Thrown when a Keycloak Admin REST or token endpoint call fails for a reason
 * that the caller can react to (HTTP error, malformed response, etc.).
 *
 * <p>Carries the upstream {@link HttpStatus} so the global exception handler
 * can choose the right envelope status without having to re-translate vendor
 * codes. The message is the resolved human-readable string — safe to surface
 * back to API consumers.</p>
 */
public class KeycloakOperationException extends RuntimeException {

    private final HttpStatus status;
    private final String operation;

    public KeycloakOperationException(String operation, String message) {
        this(operation, HttpStatus.INTERNAL_SERVER_ERROR, message, null);
    }

    public KeycloakOperationException(String operation, String message, Throwable cause) {
        this(operation, HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }

    public KeycloakOperationException(String operation, HttpStatus status, String message) {
        this(operation, status, message, null);
    }

    public KeycloakOperationException(String operation, HttpStatus status,
                                      String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.status = status;
    }

    /** Short label for the operation that triggered the error (e.g. {@code "createUser"}). */
    public String operation() {
        return operation;
    }

    /** Upstream HTTP status — empty when the error originated client-side. */
    public Optional<HttpStatus> status() {
        return Optional.ofNullable(status);
    }
}
