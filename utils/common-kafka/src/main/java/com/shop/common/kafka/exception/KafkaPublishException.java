package com.shop.common.kafka.exception;

/**
 * Thrown when publishing a record to Kafka fails unrecoverably after retries.
 */
public class KafkaPublishException extends RuntimeException {

    public KafkaPublishException(String message) {
        super(message);
    }

    public KafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
