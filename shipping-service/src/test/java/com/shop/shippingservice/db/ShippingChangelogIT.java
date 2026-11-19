package com.shop.shippingservice.db;

import com.shop.shippingservice.support.AbstractIntegrationTest;
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

class ShippingChangelogIT {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String BASE_CHANGELOG = "db/changelog/seed-base-changelog.yaml";

    @Test
    void theWholeChainAppliesToADatabaseThatAlreadyHoldsFailedWebhookEvents() throws Exception {
        String database = createDatabase();
        try {
            DataSource dataSource = dataSourceFor(database);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            apply(dataSource, BASE_CHANGELOG);

            jdbc.update("insert into shipment_events (id, carrier, provider_event_id, type, payload, "
                    + "status, version, created_at, updated_at, deleted) "
                    + "values (?, 'GHN', ?, 'carrier.status.v1', '{}', 'FAILED', 0, now(), now(), false)",
                    UUID.randomUUID(), "seed-failed-" + UUID.randomUUID());

            apply(dataSource, MASTER_CHANGELOG);
            apply(dataSource, MASTER_CHANGELOG);

            assertThat(jdbc.queryForObject(
                    "select status from shipment_events where provider_event_id like 'seed-failed-%'",
                    String.class))
                    .as("the seeded FAILED row must survive the webhook retry backfill")
                    .isEqualTo("FAILED_RETRYABLE");
            assertThat(columnExists(jdbc, "shipments", "carrier_acknowledged_at")).isTrue();
            assertThat(columnExists(jdbc, "shipment_events", "claimed_at")).isTrue();
            assertThat(columnExists(jdbc, "outbox_events", "claimed_at")).isTrue();
            assertThat(tableExists(jdbc, "shipment_creation_tasks")).isTrue();
            assertThat(indexExists(jdbc, "uk_shipment_creation_task_order")).isTrue();
            assertThat(indexExists(jdbc, "idx_shipment_creation_task_due")).isTrue();
            assertThat(indexExists(jdbc, "idx_shipment_events_status_next_retry")).isTrue();
            assertThat(constraintExists(jdbc, "fk_shipment_events_shipment")).isTrue();
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void theWholeChainAppliesToAnEmptyDatabase() throws Exception {
        String database = createDatabase();
        try {
            DataSource dataSource = dataSourceFor(database);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            apply(dataSource, MASTER_CHANGELOG);

            assertThat(tableExists(jdbc, "shipments")).isTrue();
            assertThat(tableExists(jdbc, "shipment_events")).isTrue();
            assertThat(tableExists(jdbc, "outbox_events")).isTrue();
            assertThat(tableExists(jdbc, "shipment_creation_tasks")).isTrue();
            assertThat(columnExists(jdbc, "shipments", "carrier_acknowledged_at")).isTrue();
            assertThat(columnExists(jdbc, "shipment_events", "claimed_at")).isTrue();
            assertThat(columnExists(jdbc, "outbox_events", "claimed_at")).isTrue();
            assertThat(indexExists(jdbc, "idx_shipment_events_status_next_retry")).isTrue();
            assertThat(constraintExists(jdbc, "fk_shipment_events_shipment")).isTrue();
        } finally {
            dropDatabase(database);
        }
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

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = current_schema() "
                        + "and table_name = ?", Integer.class, table);
        return count != null && count > 0;
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = current_schema() "
                        + "and table_name = ? and column_name = ?", Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean indexExists(JdbcTemplate jdbc, String index) {
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = current_schema() and indexname = ?",
                Integer.class, index);
        return count != null && count > 0;
    }

    private boolean constraintExists(JdbcTemplate jdbc, String constraint) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where constraint_schema = current_schema() and constraint_name = ?",
                Integer.class, constraint);
        return count != null && count > 0;
    }
}
