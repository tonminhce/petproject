package com.shop.authservice.exception;

import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.keycloak.exception.KeycloakClientException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(KeycloakClientException.class)
    public ResponseEntity<ApiResponse<Void>> handleKeycloakClientException(
            KeycloakClientException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus() != null ? ex.getStatus() : HttpStatus.BAD_REQUEST;
        String code = status == HttpStatus.UNAUTHORIZED
                ? ErrorCode.UNAUTHORIZED.getCode()
                : ErrorCode.BAD_REQUEST.getCode();
        String message = status == HttpStatus.UNAUTHORIZED
                ? "Invalid username or password."
                : ex.getMessage();
        return ResponseEntity.status(status).body(ApiResponse.error(code, message, request.getRequestURI()));
    }
}
