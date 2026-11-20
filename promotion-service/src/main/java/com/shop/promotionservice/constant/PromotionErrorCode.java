package com.shop.promotionservice.constant;

import com.shop.common.core.exception.ErrorCatalog;
import org.springframework.http.HttpStatus;

public enum PromotionErrorCode implements ErrorCatalog {

    PROMOTION_CURRENCY_MISMATCH("PRO-7013", "promotion.currency.mismatch", HttpStatus.BAD_REQUEST),
    PROMOTION_CURRENCY_UNSUPPORTED("PRO-7014", "promotion.currency.unsupported", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    PromotionErrorCode(String code, String messageKey, HttpStatus httpStatus) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessageKey() {
        return messageKey;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
