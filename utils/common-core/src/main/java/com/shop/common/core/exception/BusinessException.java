package com.shop.common.core.exception;

import com.shop.common.core.i18n.Messages;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    private BusinessException(ErrorCatalog code, String message) {
        super(message);
        this.status = code.getHttpStatus();
        this.errorCode = code.getCode();
    }

    private BusinessException(ErrorCatalog code, String message, Throwable cause) {
        super(message, cause);
        this.status = code.getHttpStatus();
        this.errorCode = code.getCode();
    }

    private BusinessException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    protected BusinessException(BusinessException other) {
        super(other.getMessage(), other.getCause());
        this.status = other.status;
        this.errorCode = other.errorCode;
    }

    public static BusinessException of(ErrorCatalog code, Object... messageArgs) {
        return new BusinessException(code, Messages.get(code.getMessageKey(), messageArgs));
    }

    public static BusinessException ofKey(ErrorCatalog code, String messageKey, Object... messageArgs) {
        return new BusinessException(code, Messages.get(messageKey, messageArgs));
    }

    public static BusinessException badRequest(String messageKey, Object... args) {
        return new BusinessException(ErrorCode.BAD_REQUEST, Messages.get(messageKey, args));
    }

    public static BusinessException badRequest(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.BAD_REQUEST, Messages.get(messageKey, args), cause);
    }

    public static BusinessException unauthorized(String messageKey, Object... args) {
        return new BusinessException(ErrorCode.UNAUTHORIZED, Messages.get(messageKey, args));
    }

    public static BusinessException unauthorized(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.UNAUTHORIZED, Messages.get(messageKey, args), cause);
    }

    public static BusinessException forbidden(String messageKey, Object... args) {
        return new BusinessException(ErrorCode.FORBIDDEN, Messages.get(messageKey, args));
    }

    public static BusinessException forbidden(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.FORBIDDEN, Messages.get(messageKey, args), cause);
    }

    public static BusinessException notFound(String messageKey, Object... args) {
        return new BusinessException(ErrorCode.NOT_FOUND, Messages.get(messageKey, args));
    }

    public static BusinessException notFound(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.NOT_FOUND, Messages.get(messageKey, args), cause);
    }

    public static BusinessException conflict(String messageKey, Object... args) {
        return new BusinessException(ErrorCode.CONFLICT, Messages.get(messageKey, args));
    }

    public static BusinessException conflict(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.CONFLICT, Messages.get(messageKey, args), cause);
    }

    public static BusinessException serviceUnavailable(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, Messages.get(messageKey, args), cause);
    }

    public static BusinessException internalServerError(String messageKey, Object... args) {
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, Messages.get(messageKey, args));
    }

    public static BusinessException internalServerError(String messageKey, Throwable cause, Object... args) {
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, Messages.get(messageKey, args), cause);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
