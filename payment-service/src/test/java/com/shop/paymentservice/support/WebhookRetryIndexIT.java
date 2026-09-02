package com.shop.paymentservice.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H40 — composite index proof on real Postgres. App-side @Index is ignored
 * because ddl-auto=validate (R9) keeps Hibernate from creating it; only
 * Liquibase wires the index live. This test introspects pg_indexes (the
 * cheapest authoritative source for "does this index exist") and asserts the
 * (status, next_retry_at) composite index is present after migrations run.
 */
class WebhookRetryIndexIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void compositeIndexStatusNextRetryAtIsPresent() {
        List<String> indexNames = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'payment_events'",
            String.class
        );
        assertThat(indexNames)
            .as("payment_events table must carry the H40 composite index")
            .contains("idx_webhook_retry_status_next");
    }

    @Test
    void compositeIndexColumnsAreStatusAndNextRetryAt() {
        // pg_indexes is the simplest authoritative source; the
        // indexdef column is the canonical CREATE INDEX statement.
        List<String> defs = jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_webhook_retry_status_next'",
            String.class
        );
        assertThat(defs).hasSize(1);
        String def = defs.get(0).toLowerCase();
        assertThat(def).contains("status");
        assertThat(def).contains("next_retry_at");
        // Order matters: the WHERE clause of the retry scheduler walks (status, next_retry_at)
        // so the leftmost column is the equality predicate and the rightmost is the range.
        int statusPos = def.indexOf("status");
        int nextPos = def.indexOf("next_retry_at");
        assertThat(statusPos).isLessThan(nextPos);
    }
}
