package com.shop.common.kafka.consumer;

/**
 * F-001 — a record whose payload cannot be bound to the expected event type.
 *
 * <p>Throwing this from the consumer boundary is what lets the container's
 * error handler retry the record and then route it to the dead-letter topic.
 * The previous behaviour - logging and skipping - advanced the offset as if
 * the event had been handled, so the event was lost with no DLQ entry and no
 * way to replay it.</p>
 */
public class PoisonRecordException extends RuntimeException {

    public PoisonRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
