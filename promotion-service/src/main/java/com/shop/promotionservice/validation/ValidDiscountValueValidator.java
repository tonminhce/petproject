package com.shop.promotionservice.validation;

import com.shop.promotionservice.dto.request.CampaignRequest;
import com.shop.promotionservice.service.DiscountCalculator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

public class ValidDiscountValueValidator implements ConstraintValidator<ValidDiscountValue, CampaignRequest> {

    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);

    @Override
    public boolean isValid(CampaignRequest request, ConstraintValidatorContext context) {
        if (request == null
                || request.discountType() == null
                || request.discountValue() == null) {
            return true; // field-level @NotBlank / @NotNull handle these
        }
        if (DiscountCalculator.TYPE_PERCENT.equalsIgnoreCase(request.discountType())
                && (request.discountValue().compareTo(BigDecimal.ZERO) <= 0
                    || request.discountValue().compareTo(MAX_PERCENT) > 0)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("discountValue")
                .addConstraintViolation();
            return false;
        }
        return true;
    }
}
