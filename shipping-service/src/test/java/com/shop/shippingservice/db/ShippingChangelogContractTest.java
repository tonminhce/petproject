package com.shop.shippingservice.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingChangelogContractTest {

    private static final Pattern CHANGESET_ID = Pattern.compile("^\\s*id:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    @Test
    void theMasterWidensTheEventStatusCheckBeforeTheWebhookRetryBackfill() throws IOException {
        String master = read("db.changelog-master.yaml");

        int widening = master.indexOf("file: changelog-002b-expand-events-status-check-pre.yaml");
        int retryFields = master.indexOf("file: changelog-003-webhook-retry.yaml");
        int expandedStatus = master.indexOf("file: changelog-004-expand-events-status-check.yaml");

        assertThat(widening).isNotNegative();
        assertThat(widening)
                .as("the status check must be widened before the backfill that uses the new value")
                .isLessThan(retryFields);
        assertThat(expandedStatus).isGreaterThan(retryFields);
    }

    @Test
    void theMasterIncludesTheCreationTaskTheClaimIndexAndTheForeignKey() throws IOException {
        String master = read("db.changelog-master.yaml");

        assertThat(master).contains(
                "file: changelog-007-shipment-creation-tasks.yaml",
                "file: changelog-008-webhook-retry-claim-and-index.yaml",
                "file: changelog-009-shipment-event-shipment-fk.yaml");
    }

    @Test
    void theShipmentCreationTaskTableIsCreatedWithItsUniqueOrderIndex() throws IOException {
        String changelog = read("changelog-007-shipment-creation-tasks.yaml");

        assertThat(changelog).contains("tableName: shipment_creation_tasks");
        assertThat(changelog).contains("uk_shipment_creation_task_order");
        assertThat(changelog).contains("ON shipment_creation_tasks (order_id) WHERE deleted = false");
        assertThat(changelog).contains("idx_shipment_creation_task_due");
        assertThat(changelog).contains("ON shipment_creation_tasks (status, next_attempt_at)");
    }

    @Test
    void theWebhookRetryPollIndexAndTheClaimColumnAreDeclared() throws IOException {
        String changelog = read("changelog-008-webhook-retry-claim-and-index.yaml");

        assertThat(changelog).contains("ALTER TABLE shipment_events ADD COLUMN IF NOT EXISTS claimed_at");
        assertThat(changelog).contains("idx_shipment_events_status_next_retry");
        assertThat(changelog).contains("ON shipment_events (status, next_retry_at)");
    }

    @Test
    void theShipmentEventShipmentForeignKeyIsDeclared() throws IOException {
        String changelog = read("changelog-009-shipment-event-shipment-fk.yaml");

        assertThat(changelog).contains("fk_shipment_events_shipment");
        assertThat(changelog).contains("FOREIGN KEY (shipment_id) REFERENCES shipments (id)");
        assertThat(changelog).contains("ON DELETE SET NULL");
    }

    @Test
    void everyChangesetIdInTheChainIsUnique() throws IOException {
        Path directory = locateChangelogDirectory();
        List<String> ids = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                Matcher matcher = CHANGESET_ID.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
        }

        assertThat(ids).isNotEmpty();
        assertThat(ids).doesNotHaveDuplicates();
    }

    private String read(String fileName) throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/changelog/" + fileName)) {
            assertThat(stream).as("changelog %s", fileName).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path locateChangelogDirectory() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("src").resolve("main").resolve("resources")
                    .resolve("db").resolve("changelog");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate src/main/resources/db/changelog");
    }
}
