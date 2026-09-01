package com.shop.common.logging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the audit aspect ({@link com.shop.common.logging.Audited}).
 *
 * <p>Bound from {@code shop.audit.*}. The writer's pool is deliberately NOT
 * configurable (hard-bounded core 2 / max 4 / queue 1000); the sink location
 * comes exclusively from the {@code AUDIT_LOG_PATH} environment variable.</p>
 *
 * <pre>{@code
 * shop:
 *   audit:
 *     enabled: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "shop.audit")
public class AuditLogProperties {

    /** Enable the {@link com.shop.common.logging.audit.Audited} aspect and writer. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
