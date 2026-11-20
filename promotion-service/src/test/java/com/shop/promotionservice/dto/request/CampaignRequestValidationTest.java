package com.shop.promotionservice.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import jakarta.validation.ConstraintViolation;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<CampaignRequest>> violations(CampaignRequest request) {
        return validator.validate(request);
    }

    private Set<String> messagesFor(CampaignRequest request, String property) {
        return violations(request).stream()
            .filter(v -> v.getPropertyPath().toString().equals(property))
            .map(ConstraintViolation::getMessage)
            .collect(java.util.stream.Collectors.toSet());
    }

    private CampaignRequest request(String discountType, String discountValue) {
        return new CampaignRequest(
            "SAVE10", "Save 10%", discountType, new BigDecimal(discountValue),
            null, null, null, null, null, null, null, null);
    }

    private CampaignRequest requestWithCurrency(String currency) {
        return new CampaignRequest(
            "SAVE10", "Save 10%", "PERCENT", new BigDecimal("10.00"),
            null, null, null, null, null, currency, null, null);
    }

    @Test
    @DisplayName("carry-in (a): PERCENT discountValue = 0 → violation (must be strictly positive)")
    void percentZeroDiscountValueIsRejected() {
        assertThat(messagesFor(request("PERCENT", "0.00"), "discountValue"))
            .isNotEmpty();
    }

    @Test
    @DisplayName("carry-in (a) boundary: PERCENT discountValue < 0 → violation")
    void percentNegativeDiscountValueIsRejected() {
        assertThat(messagesFor(request("PERCENT", "-5.00"), "discountValue"))
            .isNotEmpty();
    }

    @Test
    @DisplayName("carry-in (a) boundary: PERCENT discountValue > 100 → violation (existing behaviour kept)")
    void percentAboveHundredIsRejected() {
        assertThat(messagesFor(request("PERCENT", "100.01"), "discountValue"))
            .isNotEmpty();
    }

    @Test
    @DisplayName("carry-in (a) boundary: PERCENT discountValue = 100 → valid")
    void percentExactlyHundredIsValid() {
        assertThat(messagesFor(request("PERCENT", "100.00"), "discountValue"))
            .isEmpty();
    }

    @Test
    @DisplayName("carry-in (b): discountType 'percent' (wrong case) → @Pattern violation")
    void lowercaseDiscountTypeIsRejected() {
        assertThat(messagesFor(request("percent", "10.00"), "discountType"))
            .isNotEmpty();
    }

    @Test
    @DisplayName("carry-in (b): discountType 'HALF_OFF' (outside domain) → @Pattern violation")
    void unknownDiscountTypeIsRejected() {
        assertThat(messagesFor(request("HALF_OFF", "10.00"), "discountType"))
            .isNotEmpty();
    }

    @Test
    @DisplayName("FIXED discountValue = 0 → valid (lower bound applies to PERCENT only)")
    void fixedZeroDiscountValueIsValid() {
        assertThat(violations(request("FIXED", "0.00"))).isEmpty();
    }

    @Test
    @DisplayName("a supported currency is accepted")
    void supportedCurrencyIsAccepted() {
        assertThat(messagesFor(requestWithCurrency("USD"), "currency")).isEmpty();
        assertThat(messagesFor(requestWithCurrency("VND"), "currency")).isEmpty();
    }

    @Test
    @DisplayName("an unsupported currency is rejected")
    void unsupportedCurrencyIsRejected() {
        assertThat(messagesFor(requestWithCurrency("EUR"), "currency")).isNotEmpty();
        assertThat(messagesFor(requestWithCurrency("xyz"), "currency")).isNotEmpty();
    }

    @Test
    @DisplayName("a missing currency is accepted so the service can default it")
    void missingCurrencyIsAccepted() {
        assertThat(messagesFor(request("PERCENT", "10.00"), "currency")).isEmpty();
    }

    @Test
    @DisplayName("happy path: PERCENT 10 and FIXED 25 → no violations")
    void validRequestsHaveNoViolations() {
        assertThat(violations(request("PERCENT", "10.00"))).isEmpty();
        assertThat(violations(request("FIXED", "25.00"))).isEmpty();
    }
}
