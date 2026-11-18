package com.shop.common.core.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCatalog {

    String getCode();

    String getMessageKey();

    HttpStatus getHttpStatus();
}
