/**
 * Shared Kafka building blocks: producer / consumer helpers, JSON serializers,
 * retry/DLT annotations and Spring auto-configuration.
 *
 * <p>All public types are wired via {@link com.shop.common.kafka.config.KafkaAutoConfiguration}
 * which is enabled by default whenever {@code spring-kafka} is on the classpath.</p>
 */
package com.shop.common.kafka;
