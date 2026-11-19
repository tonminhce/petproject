package com.shop.taxservice.support;

import com.shop.taxservice.repository.TaxClassRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(tables).contains(
            "tax_classes",
            "tax_rates",
            "databasechangelog",
            "databasechangeloglock");

        assertThat(taxClassRepository.count()).isGreaterThanOrEqualTo(0L);
    }
}
