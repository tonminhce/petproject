package com.shop.notificationservice.support;

import com.shop.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(tables).contains(
            "notifications",
            "databasechangelog",
            "databasechangeloglock");

        assertThat(notificationRepository.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void notificationListenerFactoryBeanIsPresent() {
        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
            applicationContext.getBean("notificationListenerFactory", ConcurrentKafkaListenerContainerFactory.class);
        assertThat(factory).isNotNull();
    }
}
