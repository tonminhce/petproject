package com.shop.searchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind {@code elasticsearch.*} connection settings.
 *
 * @param url      base URL of the Elasticsearch node (e.g. {@code http://localhost:9200})
 * @param username basic-auth username; empty disables credentials (local xpack off)
 * @param password basic-auth password
 */
@ConfigurationProperties(prefix = "elasticsearch")
public record SearchProperties(String url, String username, String password) {
}
