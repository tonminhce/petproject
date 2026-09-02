package com.shop.shippingservice.db;

import com.shop.shippingservice.entity.ShipmentEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression contract for H48: every persisted shipment-event status must be
 * accepted by the database check constraint after the webhook retry migration.
 */
class ShipmentEventStatusChangelogTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String STATUS_CHANGELOG = "db/changelog/changelog-004-expand-events-status-check.yaml";
    private static final String STATUS_CHECK_MARKER = "CHECK (status IN (";

    @Test
    void masterIncludesStatusExpansionAfterWebhookRetryFields() throws IOException {
        String master = readResource(MASTER_CHANGELOG);

        int retryFieldsPosition = master.indexOf("file: changelog-003-webhook-retry.yaml");
        int expandedStatusPosition = master.indexOf("file: changelog-004-expand-events-status-check.yaml");

        assertThat(retryFieldsPosition).isNotNegative();
        assertThat(expandedStatusPosition).isGreaterThan(retryFieldsPosition);
    }

    @Test
    void statusCheckAllowsEveryShipmentEventStatus() throws IOException {
        String changelog = readResource(STATUS_CHANGELOG);
        String statusCheck = changelog.substring(changelog.indexOf(STATUS_CHECK_MARKER));

        assertThat(statusCheck).contains(
                quote(ShipmentEvent.STATUS_PROCESSED),
                quote(ShipmentEvent.STATUS_FAILED),
                quote(ShipmentEvent.STATUS_FAILED_RETRYABLE),
                quote(ShipmentEvent.STATUS_FAILED_PERMANENT));
    }

    private static String quote(String status) {
        return "'" + status + "'";
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream stream = ShipmentEventStatusChangelogTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("resource %s", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
