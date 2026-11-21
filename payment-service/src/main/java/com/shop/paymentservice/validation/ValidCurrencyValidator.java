package com.shop.paymentservice.validation;

import com.shop.common.core.constants.PlatformCurrency;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidCurrencyValidator implements ConstraintValidator<ValidCurrency, String> {

    @Override
    public boolean isValid(String currency, ConstraintValidatorContext context) {
        if (currency == null || currency.isBlank()) {
            return true;
        }
        return PlatformCurrency.isSupported(currency.trim());
    }
}
