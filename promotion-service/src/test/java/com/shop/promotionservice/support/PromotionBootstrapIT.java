package com.shop.promotionservice.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shop.promotionservice.repository.CampaignRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap smoke IT — proves the Task-12 harness boots the full promotion
 * context against real infrastructure: Testcontainers PostgreSQL + Kafka up,
 * Liquibase changelogs applied (business tables + Liquibase bookkeeping
 * present), repositories reachable against the migrated schema.
 *
 * <p>Container/property bootstrap lives entirely in
 * {@link AbstractIntegrationTest}; this class only asserts.</p>
 */
class PromotionBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CampaignRepository campaignRepository;

    @Test
    void contextBootsAndLiquibaseSchemaIsApplied() {
        assertThat(applicationContext).isNotNull();

        List<String> tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

        // Business tables from changelog-001/002 + Liquibase bookkeeping.
        assertThat(tables).contains(
            "campaign",
            "coupon_usage_reservation",
            "outbox_events",
            "databasechangelog",
            "databasechangeloglock");

        // Repository layer works against the migrated schema (fresh DB → empty).
        assertThat(campaignRepository.count()).isZero();
    }
}
