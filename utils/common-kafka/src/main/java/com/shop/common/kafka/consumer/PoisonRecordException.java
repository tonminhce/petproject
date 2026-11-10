package com.shop.common.kafka.consumer;

public class PoisonRecordException extends RuntimeException {

    public PoisonRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
