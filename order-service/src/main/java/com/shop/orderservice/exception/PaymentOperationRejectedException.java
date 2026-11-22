package com.shop.orderservice.exception;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCatalog;

public class PaymentOperationRejectedException extends BusinessException {

    public PaymentOperationRejectedException(ErrorCatalog code, Object... messageArgs) {
        super(BusinessException.of(code, messageArgs));
    }
}
