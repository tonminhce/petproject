package com.shop.common.kafka.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPropertiesConsumerCommitSemanticsTest {

    @Test
    void buildConsumerPropertiesPinsTheCommitSemantics() {
        KafkaProperties props = new KafkaProperties();
        Map<String, Object> consumer = props.buildConsumerProperties();

        assertThat(consumer)
                .as("the offset-commit semantics must be an explicit configuration, not a framework default")
                .containsEntry("enable.auto.commit", false)
                .containsEntry("isolation.level", "read_committed");
    }

    @Test
    void buildConsumerPropertiesPinsThePollBudget() {
        KafkaProperties props = new KafkaProperties();
        Map<String, Object> consumer = props.buildConsumerProperties();

        assertThat(consumer.get("max.poll.records")).isInstanceOf(Integer.class);
        assertThat((Integer) consumer.get("max.poll.records")).isBetween(1, 10_000);
        assertThat(consumer.get("max.poll.interval.ms")).isInstanceOf(Integer.class);
        assertThat((Integer) consumer.get("max.poll.interval.ms")).isGreaterThan(0);
        assertThat(consumer.get("session.timeout.ms")).isInstanceOf(Integer.class);
        assertThat((Integer) consumer.get("session.timeout.ms")).isGreaterThan(0);
    }

    @Test
    void theCommitSemanticsAreConfigurableAndDefaultToTheSafePosture() {
        KafkaProperties props = new KafkaProperties();

        assertThat(props.getConsumer().isEnableAutoCommit()).isFalse();
        assertThat(props.getConsumer().getIsolationLevel()).isEqualTo("read_committed");

        props.getConsumer().setEnableAutoCommit(true);
        props.getConsumer().setIsolationLevel("read_uncommitted");

        Map<String, Object> consumer = props.buildConsumerProperties();
        assertThat(consumer)
                .containsEntry("enable.auto.commit", true)
                .containsEntry("isolation.level", "read_uncommitted");
    }
}
