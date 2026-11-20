package com.shop.gateway.resilience;

public final class DownstreamServerErrorException extends RuntimeException {

    public DownstreamServerErrorException(final String message) {
        super(message);
    }
}
