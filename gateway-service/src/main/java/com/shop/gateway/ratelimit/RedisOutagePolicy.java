package com.shop.gateway.ratelimit;

public enum RedisOutagePolicy {

    FAIL_OPEN,
    FAIL_CLOSED;

    public boolean failsOpen() {
        return this == FAIL_OPEN;
    }
}
