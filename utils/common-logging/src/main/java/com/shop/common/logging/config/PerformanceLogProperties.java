package com.shop.common.logging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AOP performance logging aspect ({@link com.shop.common.logging.LogPerformance}).
 *
 * <p>Bound from {@code shop.web.logging.performance.*}.</p>
 *
 * <pre>{@code
 * shop:
 *   web:
 *     logging:
 *       performance:
 *         enabled: true
 *         threshold-ms: 50
 * }</pre>
 */
@ConfigurationProperties(prefix = "shop.web.logging.performance")
public class PerformanceLogProperties {

    /** Enable the {@link com.shop.common.logging.LogPerformance} / {@link com.shop.common.logging.Loggable} aspect. */
    private boolean enabled = true;

    /** Only log when execution exceeds this threshold. Fast calls stay silent. */
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
