package com.shop.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a referenced resource cannot be located. Translates to {@link HttpStatus#NOT_FOUND}.
 *
 * <p>A thin specialization of {@link BusinessException} so callers can catch a single
 * type in controllers while still routing through the canonical {@link ErrorCode#NOT_FOUND}
 * error in the response envelope.</p>
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String messageKey, Object... args) {
        super(BusinessException.notFound(messageKey, args));
    }
}
