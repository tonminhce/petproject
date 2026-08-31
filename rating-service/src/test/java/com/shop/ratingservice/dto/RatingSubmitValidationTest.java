package com.shop.ratingservice.dto;

import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RatingSubmitValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private final UUID productId = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private RatingSubmitRequest valid() {
        return new RatingSubmitRequest(productId, 5, "Great product, really enjoyed it");
    }

    @Test
    void validRequest_hasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void nullProductId_violates() {
        var request = new RatingSubmitRequest(null, 5, "Great product, really enjoyed it");
        assertThat(validator.validate(request))
                .anyMatch(v -> v.getPropertyPath().toString().equals("productId"));
    }

    @Test
    void ratingBounds_enforced() {
        assertThat(validator.validate(new RatingSubmitRequest(productId, 0, "Great product, really enjoyed it")))
                .anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
        assertThat(validator.validate(new RatingSubmitRequest(productId, 6, "Great product, really enjoyed it")))
                .anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
        assertThat(validator.validate(new RatingSubmitRequest(productId, 1, "Great product, really enjoyed it")))
                .noneMatch(v -> v.getPropertyPath().toString().equals("rating"));
        assertThat(validator.validate(new RatingSubmitRequest(productId, 5, "Great product, really enjoyed it")))
                .noneMatch(v -> v.getPropertyPath().toString().equals("rating"));
    }

    @Test
    void commentLengthBounds_enforced() {
        var tooShort = new RatingSubmitRequest(productId, 5, "meh");
        assertThat(validator.validate(tooShort))
                .anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
        var tooLong = new RatingSubmitRequest(productId, 5, "x".repeat(2001));
        assertThat(validator.validate(tooLong))
                .anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    @Test
    void nullRatingAndComment_violate() {
        var request = new RatingSubmitRequest(productId, null, null);
        var violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }
}
