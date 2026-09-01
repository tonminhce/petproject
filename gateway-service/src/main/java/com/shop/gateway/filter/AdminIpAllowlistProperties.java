package com.shop.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * D5 — backoffice IP allowlist, sourced from the {@code ADMIN_IP_ALLOWLIST}
 * environment variable (comma-separated CIDRs) via application.yml.
 *
 * <p><strong>Binding semantics:</strong> env ABSENT (or present-but-empty) =
 * filter INACTIVE (pass-through, dev-friendly default); env PRESENT with at
 * least one parseable CIDR = deny any non-matching source with a 403 envelope.
 * Unparseable entries fail gateway startup — a security control must never
 * silently degrade to INACTIVE.</p>
 */
@ConfigurationProperties(prefix = "gateway.admin-ip-allowlist")
public record AdminIpAllowlistProperties(@DefaultValue List<String> cidrs) {

    public AdminIpAllowlistProperties {
        cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
    }

    /**
     * True when the allowlist is enforcing (env present with content).
     */
    public boolean active() {
        return !cidrs.isEmpty();
    }
}
