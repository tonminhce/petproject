package com.shop.taxservice.support;

import com.shop.taxservice.repository.TaxClassRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap smoke IT — proves the harness boots the full tax-service
 * context against real infrastructure: Testcontainers PostgreSQL up,
 * Liquibase changelog applied (business tables + Liquibase bookkeeping
 * present), repositories reachable against the migrated schema.
 *
 * <p>Container/property bootstrap lives entirely in
 * {@link AbstractIntegrationTest}; this class only asserts.</p>
 */
class TaxBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaxClassRepository taxClassRepository;

    @Test
    void contextBootsAndLiquibaseSchemaIsApplied() {
        assertThat(applicationContext).isNotNull();

        List<String> tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

        // Business tables from changelog-001 + Liquibase bookkeeping.
        assertThat(tables).contains(
            "tax_classes",
            "tax_rates",
            "databasechangelog",
            "databasechangeloglock");

        // Repository layer works against the migrated schema.
        assertThat(taxClassRepository.count()).isGreaterThanOrEqualTo(0L);
    }
}
