package com.shop.orderservice.exception;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

import java.util.UUID;

public class ProductLookupRejectedException extends BusinessException {

    public ProductLookupRejectedException(UUID productId) {
        super(BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, productId));
    }
}
