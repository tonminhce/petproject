package com.shop.paymentservice.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCurrencyChangelogContractTest {

    @Test
    void theExistingCurrenciesAreNormalisedBeforeTheConstraintIsAdded() throws IOException {
        String changelog = read("changelog-011-add-payment-currency.yaml");

        int normalisation = changelog.indexOf("UPDATE payments SET currency = 'VND'");
        int upperCasing = changelog.indexOf("UPDATE payments SET currency = UPPER(TRIM(currency))");
        int constraint = changelog.indexOf("ADD CONSTRAINT ck_payments_currency CHECK");

        assertThat(normalisation).isNotNegative();
        assertThat(upperCasing).isNotNegative();
        assertThat(constraint).isNotNegative();
        assertThat(normalisation)
                .as("a currency outside the supported set must be normalised before the constraint is added")
                .isLessThan(constraint);
        assertThat(upperCasing)
                .as("a lowercase currency must be upper cased before the constraint is added")
                .isLessThan(constraint);
    }

    @Test
    void theRefundedCurrencyIsBackfilledFromTheCurrencyOfItsOwnRowBeforeItBecomesMandatory() throws IOException {
        String changelog = read("changelog-011-add-payment-currency.yaml");

        int backfill = changelog.indexOf("UPDATE payments SET refunded_currency = currency");
        int notNull = changelog.indexOf("ALTER COLUMN refunded_currency SET NOT NULL");

        assertThat(backfill).isNotNegative();
        assertThat(notNull).isNotNegative();
        assertThat(backfill)
                .as("the refunded currency must be backfilled from the currency of its own row")
                .isLessThan(notNull);
        assertThat(changelog)
                .as("the refunded currency must not be created with a constant")
                .doesNotContain("defaultValue: 'VND'");
    }

    private String read(String fileName) throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/changelog/" + fileName)) {
            assertThat(stream).as("changelog %s", fileName).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
