package com.shop.notificationservice.support;

import com.shop.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap smoke IT — proves the Task-9 harness boots the full
 * notification-service context against real infrastructure: Testcontainers
 * PostgreSQL + Kafka up, Liquibase changelog applied (business table +
 * Liquibase bookkeeping present), repositories reachable against the migrated
 * schema, and the {@code notificationListenerFactory} consumer wiring present
 * so {@code OrderEventConsumer} can attach.
 *
 * <p>Container/property bootstrap lives entirely in
 * {@link AbstractIntegrationTest}; this class only asserts.</p>
 *
 * <p>{@code @DirtiesContext}: this context runs a live {@code OrderEventConsumer}
 * in group {@code notification-service}. Closing it after the class stops that
 * consumer from competing for the topic's single partition while
 * {@code NotificationFlowIT} runs — flow scenarios (e.g. the spied sender)
 * must be processed by the flow context's own listener.</p>
 */
@DirtiesContext
class NotificationBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void contextBootsAndLiquibaseSchemaIsApplied() {
        assertThat(applicationContext).isNotNull();

        List<String> tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

        // Business table from changelog-001 + Liquibase bookkeeping.
        assertThat(tables).contains(
            "notifications",
            "databasechangelog",
            "databasechangeloglock");

        // Repository layer reachable against the migrated schema. The singleton
        // containers are shared across sibling ITs (scheduler/NotificationRetryIT
        // runs before this class in surefire's filesystem order), so sibling rows
        // may already exist — only prove the query executes against the schema.
        assertThat(notificationRepository.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void notificationListenerFactoryBeanIsPresent() {
        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
            applicationContext.getBean("notificationListenerFactory", ConcurrentKafkaListenerContainerFactory.class);
        assertThat(factory).isNotNull();
    }
}
