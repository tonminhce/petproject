package com.shop.shippingservice.support;

import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.carrier.ManualCarrierAdapter;
import com.shop.shippingservice.scheduler.ReconciliationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap smoke IT — proves the Task-12 harness boots the full
 * shipping-service context against real infrastructure: Testcontainers
 * PostgreSQL + Kafka up, Liquibase changelog applied (business tables +
 * Liquibase bookkeeping present), the {@code shippingListenerFactory}
 * consumer wiring present with a live listener container so
 * {@code OrderEventConsumer} can attach, the manual carrier adapter active,
 * and the reconciliation scheduler bean present.
 *
 * <p>Container/property bootstrap lives entirely in
 * {@link AbstractIntegrationTest}; this class only asserts.</p>
 *
 * <p>{@code @DirtiesContext}: this context runs a live
 * {@code OrderEventConsumer} in group {@code shipping-service}. Closing it
 * after the class stops that consumer from competing for the topic's single
 * partition while later flow ITs run — flow scenarios must be processed by
 * the flow context's own listener.</p>
 */
@DirtiesContext
class ShippingBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CarrierAdapter carrierAdapter;

    @Autowired
    private ReconciliationScheduler reconciliationScheduler;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Test
    void contextBootsAndLiquibaseSchemaIsApplied() {
        assertThat(applicationContext).isNotNull();

        List<String> tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

        // Business tables from changelog-001/-002 + Liquibase bookkeeping.
        assertThat(tables).contains(
            "shipments",
            "shipment_events",
            "outbox_events",
            "databasechangelog",
            "databasechangeloglock");
    }

    @Test
    void shippingListenerFactoryBeanIsPresent() {
        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
            applicationContext.getBean("shippingListenerFactory", ConcurrentKafkaListenerContainerFactory.class);
        assertThat(factory).isNotNull();
    }

    @Test
    void kafkaConsumersAreLive() {
        Collection<MessageListenerContainer> containers = kafkaListenerEndpointRegistry.getListenerContainers();
        assertThat(containers).isNotEmpty();
        assertThat(containers).allMatch(MessageListenerContainer::isRunning);
    }

    @Test
    void manualCarrierAdapterIsActive() {
        assertThat(carrierAdapter).isInstanceOf(ManualCarrierAdapter.class);
    }

    @Test
    void reconciliationSchedulerBeanIsPresent() {
        assertThat(reconciliationScheduler).isNotNull();
    }
}
