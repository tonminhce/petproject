package com.shop.common.security.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesCorsMaxAgeTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private SecurityProperties.Cors cors(long maxAgeSeconds) {
        return new SecurityProperties.Cors(
                true,
                List.of("https://shop.example"),
                List.of("GET"),
                List.of("Authorization"),
                List.of(),
                false,
                maxAgeSeconds);
    }

    @Test
    @DisplayName("a max-age of 0 is accepted (the preflight response is simply not cached)")
    void aMaxAgeOfZeroIsAccepted() {
        Set<ConstraintViolation<SecurityProperties.Cors>> violations =
                validator.validate(cors(0L));

        assertThat(violations)
                .as("maxAgeSeconds = 0 must be a valid configuration")
                .isEmpty();
    }

    @Test
    @DisplayName("a positive max-age is accepted")
    void aPositiveMaxAgeIsAccepted() {
        assertThat(validator.validate(cors(3600L))).isEmpty();
    }

    @Test
    @DisplayName("a negative max-age is rejected")
    void aNegativeMaxAgeIsRejected() {
        Set<ConstraintViolation<SecurityProperties.Cors>> violations =
                validator.validate(cors(-1L));

        assertThat(violations)
                .as("maxAgeSeconds must not be negative")
                .isNotEmpty();
    }
}
