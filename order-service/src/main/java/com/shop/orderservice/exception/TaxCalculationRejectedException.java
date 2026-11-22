package com.shop.orderservice.exception;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

public class TaxCalculationRejectedException extends BusinessException {

    public TaxCalculationRejectedException(String detail) {
        super(BusinessException.of(ErrorCode.ORDER_TAX_CALCULATION_FAILED, detail));
    }
}
