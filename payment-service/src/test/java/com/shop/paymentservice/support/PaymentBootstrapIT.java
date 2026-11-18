package com.shop.paymentservice.support;

import com.shop.paymentservice.provider.MockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextBootsAndLiquibaseSchemaIsApplied() {
        assertThat(applicationContext).isNotNull();

        List<String> tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

        assertThat(tables).contains(
            "payments",
            "payment_events",
            "outbox_events",
            "databasechangelog",
            "databasechangeloglock");
    }

    @Test
    void providerBeanIsMockProvider() {
        Object provider = applicationContext.getBean("mockProvider");
        assertThat(provider).isInstanceOf(MockProvider.class);
    }

    @Test
    void kafkaTemplateBeanIsPresent() {
        assertThat(applicationContext.containsBean("kafkaTemplate")).isTrue();
    }
}
