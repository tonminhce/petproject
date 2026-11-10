package com.shop.common.kafka.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPropertiesProducerDefaultsTest {

    @Test
    void buildProducerPropertiesPinsBatchLingerCompressionAndIdempotence() {
        KafkaProperties props = new KafkaProperties();
        Map<String, Object> producer = props.buildProducerProperties();

        assertThat(producer)
                .containsEntry("compression.type", "lz4")
                .containsEntry("enable.idempotence", true);
        // batch.size and linger.ms are non-zero ints; assert presence and a sane value.
        assertThat(producer.get("batch.size")).isInstanceOf(Integer.class);
        assertThat((Integer) producer.get("batch.size"))
                .as("batch.size default — pinned to amortise network round-trips")
                .isGreaterThanOrEqualTo(16 * 1024);
        assertThat(producer.get("linger.ms")).isInstanceOf(Integer.class);
        assertThat((Integer) producer.get("linger.ms"))
                .as("linger.ms default — small wait so micro-batches still form")
                .isBetween(1, 100);
    }

    @Test
    void buildProducerPropertiesKeepsAcksAllAndExactlyOnceByDefault() {
        KafkaProperties props = new KafkaProperties();
        Map<String, Object> producer = props.buildProducerProperties();

        // acks=all + enable.idempotence=true + max.in.flight.requests.per.connection=1
        // is the canonical exactly-once-per-attempt recipe for the fleet.
        assertThat(producer).containsEntry("acks", "all");
        assertThat(producer).containsEntry("enable.idempotence", true);
        assertThat(producer)
                .containsEntry("max.in.flight.requests.per.connection", 1);
    }

    @Test
    void producerDefaultsKeepStringKeySerializer() {
        // Wire contract pin (R1): the producer properties must continue to
        // advertise a String key serializer (the value serializer is added
        // downstream by KafkaAutoConfiguration.stringKafkaTemplate so the
        // typed <String,String> wire stays single-encoded JSON).
        KafkaProperties props = new KafkaProperties();
        Map<String, Object> producer = props.buildProducerProperties();

        assertThat(producer.get("key.serializer"))
                .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
    }
}