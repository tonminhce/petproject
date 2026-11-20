package com.shop.paymentservice.db;

import com.shop.paymentservice.support.AbstractIntegrationTest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCurrencyChangelogIT {

    private static final String BASE_CHANGELOG = "db/changelog/changelog-001-payments.yaml";
    private static final String PARTIAL_REFUND_CHANGELOG = "db/changelog/changelog-008-partial-refund.yaml";
    private static final String CURRENCY_CHANGELOG = "db/changelog/changelog-011-add-payment-currency.yaml";

    @Test
    void theCurrencyConstraintAppliesToAPaymentThatHeldAForeignCurrency() throws Exception {
        String database = createDatabase();
        try {
            DataSource dataSource = dataSourceFor(database);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            apply(dataSource, BASE_CHANGELOG);
            apply(dataSource, PARTIAL_REFUND_CHANGELOG);

            insertPayment(jdbc, "seed-eur", "EUR");
            insertPayment(jdbc, "seed-usd", "USD");

            apply(dataSource, CURRENCY_CHANGELOG);

            assertThat(jdbc.queryForObject(
                    "select currency from payments where idempotency_key = 'seed-eur'", String.class))
                    .as("a currency outside the supported set must be normalised so the constraint applies")
                    .isEqualTo("VND");
            assertThat(jdbc.queryForObject(
                    "select refunded_currency from payments where idempotency_key = 'seed-eur'", String.class))
                    .as("the refunded currency must be backfilled from the currency of its own row")
                    .isEqualTo("VND");
            assertThat(jdbc.queryForObject(
                    "select refunded_currency from payments where idempotency_key = 'seed-usd'", String.class))
                    .as("a refunded amount must not be tagged with a currency other than the one of its row")
                    .isEqualTo("USD");
            assertThat(constraintExists(jdbc, "ck_payments_currency")).isTrue();
            assertThat(constraintExists(jdbc, "ck_payments_refunded_currency")).isTrue();
        } finally {
            dropDatabase(database);
        }
    }

    private void insertPayment(JdbcTemplate jdbc, String idempotencyKey, String currency) {
        jdbc.update("insert into payments (id, order_id, amount, currency, status, provider, idempotency_key, "
                + "refunded_amount, version, created_at, updated_at, deleted) "
                + "values (?, ?, 100.00, ?, 'REFUNDED', 'stripe', ?, 50.00, 0, now(), now(), false)",
                UUID.randomUUID(), UUID.randomUUID(), currency, idempotencyKey);
    }

    private String createDatabase() {
        String database = "changelog_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(baseUrl(),
                AbstractIntegrationTest.postgresUsername(),
                AbstractIntegrationTest.postgresPassword()))
                .execute("create database " + database);
        return database;
    }

    private void dropDatabase(String database) {
        new JdbcTemplate(new DriverManagerDataSource(baseUrl(),
                AbstractIntegrationTest.postgresUsername(),
                AbstractIntegrationTest.postgresPassword()))
                .execute("drop database if exists " + database + " with (force)");
    }

    private DataSource dataSourceFor(String database) {
        return new DriverManagerDataSource(withDatabase(baseUrl(), database),
                AbstractIntegrationTest.postgresUsername(),
                AbstractIntegrationTest.postgresPassword());
    }

    private static String baseUrl() {
        return AbstractIntegrationTest.postgresJdbcUrl();
    }

    private static String withDatabase(String url, String database) {
        int lastSlash = url.lastIndexOf('/');
        return url.substring(0, lastSlash + 1) + database;
    }

    private void apply(DataSource dataSource, String changeLog) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(changeLog,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private boolean constraintExists(JdbcTemplate jdbc, String constraint) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where constraint_schema = current_schema() and constraint_name = ?",
                Integer.class, constraint);
        return count != null && count > 0;
    }
}
