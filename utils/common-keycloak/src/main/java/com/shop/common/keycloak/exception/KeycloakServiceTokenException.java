package com.shop.common.keycloak.exception;

public class KeycloakServiceTokenException extends RuntimeException {

    public KeycloakServiceTokenException(String message) {
        super(message);
    }

    public KeycloakServiceTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
