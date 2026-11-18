package com.shop.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.admin-ip-allowlist")
public record AdminIpAllowlistProperties(@DefaultValue List<String> cidrs) {

    public AdminIpAllowlistProperties {
        cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
    }

    public boolean active() {
        return !cidrs.isEmpty();
    }
}
