package com.shop.searchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "elasticsearch")
public record SearchProperties(String url, String username, String password) {
}
