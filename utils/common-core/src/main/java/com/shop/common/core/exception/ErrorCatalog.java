package com.shop.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Common contract for domain error definitions, enabling decentralized
 * microservice error catalogs while preserving unified API error translation.
 */
public interface ErrorCatalog {

    String getCode();

    String getMessageKey();

    HttpStatus getHttpStatus();
}
