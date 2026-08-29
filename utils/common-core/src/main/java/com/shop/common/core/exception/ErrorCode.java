package com.shop.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Centralized error catalog. Each entry is the contract between services and clients:
 * a stable machine-readable {@code code}, an i18n message key, and the HTTP status to return.
 *
 * <p>Why an enum: gives compile-time exhaustiveness checks at every {@code switch} site
 * and prevents typos in {@code code} / {@code messageKey} drift.</p>
 */
public enum ErrorCode {

    // ---- Generic ----
    BAD_REQUEST("ERR-0400", "error.bad.request", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("ERR-0401", "error.unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("ERR-0403", "error.forbidden", HttpStatus.FORBIDDEN),
    NOT_FOUND("ERR-0404", "error.not.found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("ERR-0405", "error.method.not.allowed", HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE("ERR-0406", "error.not.acceptable", HttpStatus.NOT_ACCEPTABLE),
    CONFLICT("ERR-0409", "error.conflict", HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE("ERR-0415", "error.unsupported.media.type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    UNPROCESSABLE_ENTITY("ERR-0422", "error.unprocessable.entity", HttpStatus.UNPROCESSABLE_ENTITY),
    VALIDATION_FAILED("ERR-0422-V", "error.validation.failed", HttpStatus.BAD_REQUEST),
    TOO_MANY_REQUESTS("ERR-0429", "error.too.many.requests", HttpStatus.TOO_MANY_REQUESTS),
    PAYLOAD_TOO_LARGE("ERR-0413", "error.payload.too.large", HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL_SERVER_ERROR("ERR-0500", "error.internal.server", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE("ERR-0503", "error.service.unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    GATEWAY_TIMEOUT("ERR-0504", "error.gateway.timeout", HttpStatus.GATEWAY_TIMEOUT),
    ACCESS_DENIED("ERR-0403-A", "access.denied", HttpStatus.FORBIDDEN),

    // ---- Auth domain ----
    AUTH_TOKEN_INVALID("AUTH-1001", "auth.token.invalid", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED("AUTH-1002", "auth.token.expired", HttpStatus.UNAUTHORIZED),
    AUTH_USERNAME_EXISTS("AUTH-1003", "auth.username.exists", HttpStatus.CONFLICT),
    AUTH_EMAIL_EXISTS("AUTH-1004", "auth.email.exists", HttpStatus.CONFLICT),
    AUTH_PHONE_EXISTS("AUTH-1005", "auth.phone.exists", HttpStatus.CONFLICT),
    AUTH_USER_NOT_FOUND("AUTH-1006", "auth.user.not.found", HttpStatus.NOT_FOUND),
    AUTH_ROLE_NOT_FOUND("AUTH-1007", "auth.role.not.found", HttpStatus.NOT_FOUND),
    AUTH_PASSWORD_MANAGED_BY_KEYCLOAK("AUTH-1008", "auth.password.managed.by.keycloak", HttpStatus.BAD_REQUEST),
    AUTH_INVALID_CREDENTIALS("AUTH-1009", "auth.invalid.credentials", HttpStatus.UNAUTHORIZED),

    // ---- Product domain ----
    PRODUCT_NOT_FOUND("PRD-2001", "product.not.found", HttpStatus.NOT_FOUND),
    PRODUCT_NAME_EXISTS("PRD-2002", "product.name.exists", HttpStatus.CONFLICT),
    CATEGORY_NOT_FOUND("PRD-2003", "category.not.found", HttpStatus.NOT_FOUND),
    PRODUCT_SLUG_EXISTS("PRD-2004", "product.slug.exists", HttpStatus.CONFLICT),
    PRODUCT_SKU_EXISTS("PRD-2005", "product.sku.exists", HttpStatus.CONFLICT),
    BRAND_NOT_FOUND("PRD-2006", "brand.not.found", HttpStatus.NOT_FOUND),
    BRAND_SLUG_EXISTS("PRD-2007", "brand.slug.exists", HttpStatus.CONFLICT),
    CATEGORY_SLUG_EXISTS("PRD-2008", "category.slug.exists", HttpStatus.CONFLICT),

    // ---- Inventory domain ----
    WAREHOUSE_NOT_FOUND("INV-3001", "warehouse.not.found", HttpStatus.NOT_FOUND),
    STOCK_INSUFFICIENT("INV-3002", "stock.insufficient", HttpStatus.CONFLICT),
    RESERVATION_NOT_FOUND("INV-3003", "reservation.not.found", HttpStatus.NOT_FOUND),
    RESERVATION_EXPIRED("INV-3004", "reservation.expired", HttpStatus.CONFLICT),
    RESERVATION_INVALID_STATE("INV-3005", "reservation.invalid.state", HttpStatus.CONFLICT),
    INVENTORY_ALREADY_EXISTS("INV-3006", "inventory.already.exists", HttpStatus.CONFLICT),
    INVENTORY_VERSION_CONFLICT("INV-3007", "inventory.version.conflict", HttpStatus.CONFLICT),
    INVENTORY_NOT_FOUND("INV-3008", "inventory.not.found", HttpStatus.NOT_FOUND),
    INVENTORY_IN_USE("INV-3009", "inventory.in.use", HttpStatus.CONFLICT),

    // ---- Order domain ----
    ORDER_NOT_FOUND("ORD-4001", "order.not.found", HttpStatus.NOT_FOUND),
    CART_NOT_FOUND("ORD-4002", "cart.not.found", HttpStatus.NOT_FOUND),

    // ---- Payment domain ----
    PAYMENT_FAILED("PAY-5001", "payment.failed", HttpStatus.BAD_REQUEST),
    PAYMENT_NOT_FOUND("PAY-5002", "payment.not.found", HttpStatus.NOT_FOUND),

    // ---- Favourite domain ---- (range 6xxx — PAY already owns 5xxx)
    FAVOURITE_NOT_FOUND("FAV-6001", "favourite.not.found", HttpStatus.NOT_FOUND),
    FAVOURITE_ALREADY_EXISTS("FAV-6002", "favourite.already.exists", HttpStatus.CONFLICT),
    FAVOURITE_PRODUCT_NOT_FOUND("FAV-6003", "favourite.product.not.found", HttpStatus.NOT_FOUND),

    // ---- Order domain (continued) — ORD-4003..4010 ----
    ORDER_INVALID_STATE("ORD-4003", "order.invalid.state", HttpStatus.CONFLICT),
    ORDER_INVALID_STATE_TRANSITION("ORD-4004", "order.invalid.transition", HttpStatus.CONFLICT),
    CART_EMPTY("ORD-4005", "cart.empty", HttpStatus.CONFLICT),
    CART_ITEM_NOT_FOUND("ORD-4006", "cart.item.not.found", HttpStatus.NOT_FOUND),
    ORDER_RESERVATION_FAILED("ORD-4007", "order.reservation.failed", HttpStatus.CONFLICT),
    ORDER_PROMOTION_INVALID("ORD-4008", "order.promotion.invalid", HttpStatus.BAD_REQUEST),
    ORDER_TAX_CALCULATION_FAILED("ORD-4009", "order.tax.calculation.failed", HttpStatus.BAD_REQUEST),
    ORDER_DUPLICATE_REQUEST("ORD-4010", "order.duplicate.request", HttpStatus.CONFLICT);

    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String messageKey, HttpStatus httpStatus) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
