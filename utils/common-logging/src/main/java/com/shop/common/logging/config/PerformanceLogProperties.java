package com.shop.common.logging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.web.logging.performance")
public class PerformanceLogProperties {

    private boolean enabled = true;

    private long thresholdMs = 50L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getThresholdMs() {
        return thresholdMs;
    }

    public void setThresholdMs(long thresholdMs) {
        if (thresholdMs < 0) {
            throw new IllegalArgumentException("thresholdMs must be >= 0");
        }
        this.thresholdMs = thresholdMs;
    }
}
