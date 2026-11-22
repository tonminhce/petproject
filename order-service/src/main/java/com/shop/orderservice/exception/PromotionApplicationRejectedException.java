package com.shop.orderservice.exception;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

public class PromotionApplicationRejectedException extends BusinessException {

    public PromotionApplicationRejectedException(String couponCode) {
        super(BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, couponCode));
    }
}
