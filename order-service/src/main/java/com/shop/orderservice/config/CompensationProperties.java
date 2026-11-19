package com.shop.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.compensation")
public record CompensationProperties(
        Long retryBackoffSeconds,
        Long claimLeaseSeconds,
        Integer maxAttempts,
        Integer batchSize
) {

    public CompensationProperties {
        if (retryBackoffSeconds == null || retryBackoffSeconds <= 0) {
            retryBackoffSeconds = 60L;
        }
        if (claimLeaseSeconds == null || claimLeaseSeconds <= 0) {
            claimLeaseSeconds = 120L;
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (batchSize == null || batchSize <= 0) {
            batchSize = 50;
        }
    }
}
