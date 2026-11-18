package com.shop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateway.security")
public record GatewayJwtProperties(
        @DefaultValue List<String> expectedClientIds,
        @DefaultValue List<String> expectedAudiences
) {
}
