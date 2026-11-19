package com.shop.paymentservice.support;

import com.shop.paymentservice.provider.MockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap smoke IT — proves the Task-13 harness boots the full
 * payment-service context against real infrastructure: Testcontainers
 * PostgreSQL + Kafka up, Liquibase changelog applied (business tables +
 * Liquibase bookkeeping present), the {@code shop.payment.provider=mock}
 * override selects {@code MockProvider}, and the outbox relay's
 * {@code KafkaTemplate} wiring is present so {@code PaymentOutboxRelay}
 * can publish.
 *
 * <p>Container/property bootstrap lives entirely in
 * {@link AbstractIntegrationTest}; this class only asserts.</p>
 */
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
