package com.shop.common.core.exception;

import com.shop.common.core.i18n.Messages;
import org.springframework.http.HttpStatus;

/**
 * Single business-level exception thrown by services. Carries:
 * <ul>
 *   <li>{@link ErrorCode} — machine-readable code + HTTP status</li>
 *   <li>Resolved, locale-aware message (computed eagerly at throw site)</li>
 * </ul>
 *
 * <h3>Throw sites — pick the smallest API that fits</h3>
 * <pre>{@code
 * throw BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND);                     // canonical
 * throw BusinessException.of(ErrorCode.AUTH_USERNAME_EXISTS, "alice");         // with i18n args
 * throw BusinessException.notFound("auth.user.not.found.with.username", "x"); // ad-hoc + status
 * }</pre>
 *
 * <p>We <strong>do not</strong> wrap an arbitrary message + arbitrary status in a no-code
 * constructor — every error must map to an {@link ErrorCode} so dashboards stay consistent.</p>
 */
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

    /**
     * Copy constructor used by subclasses (e.g. {@link ResourceNotFoundException}) to
     * inherit the HTTP status and error code resolved by the static factory path.
     * The cause is taken from {@code other.getCause()} so the original exception
     * stays the root failure recorded by callers.
     */
    protected BusinessException(BusinessException other) {
        super(other.getMessage(), other.getCause());
        this.status = other.status;
        this.errorCode = other.errorCode;
    }

    // ------------------------------------------------------------------
    // Canonical factory — preferred entry point
    // ------------------------------------------------------------------

    public static BusinessException of(ErrorCatalog code, Object... messageArgs) {
        return new BusinessException(code, Messages.get(code.getMessageKey(), messageArgs));
    }

    /**
     * Use when a single {@link ErrorCatalog} maps to multiple i18n keys depending on context
     * (e.g. {@code AUTH_USER_NOT_FOUND} surfaces as different sentences per endpoint).
     */
    public static BusinessException ofKey(ErrorCatalog code, String messageKey, Object... messageArgs) {
        return new BusinessException(code, Messages.get(messageKey, messageArgs));
    }

    // ------------------------------------------------------------------
    // Status shortcuts — all funnel through ErrorCode so the catalog stays canonical
    // ------------------------------------------------------------------

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
