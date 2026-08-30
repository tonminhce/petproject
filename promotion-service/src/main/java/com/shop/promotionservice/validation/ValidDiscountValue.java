package com.shop.promotionservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint: a PERCENT campaign's {@code discountValue} must be
 * strictly positive and not exceed 100 (0% is a no-op coupon — the inclusive
 * field-level {@code @DecimalMin("0")} lets it through — and a percentage above
 * 100% is nonsensical and would create money).
 */
@Documented
@Constraint(validatedBy = ValidDiscountValueValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDiscountValue {

    String message() default "PERCENT discount value must be > 0 and <= 100";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
