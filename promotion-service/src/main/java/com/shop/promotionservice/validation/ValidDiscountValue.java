package com.shop.promotionservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint: a PERCENT campaign's {@code discountValue} must not
 * exceed 100 (a percentage above 100% is nonsensical and would create money).
 */
@Documented
@Constraint(validatedBy = ValidDiscountValueValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDiscountValue {

    String message() default "PERCENT discount value must be <= 100";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
