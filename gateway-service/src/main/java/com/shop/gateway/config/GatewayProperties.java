package com.shop.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@ConfigurationProperties(prefix = "gateway")
@Validated
public record GatewayProperties(

    @NotBlank
    String keycloakIssuerUri,

    @NotEmpty
    List<String> corsAllowedOriginPatterns,

    @NotEmpty
    List<String> publicEndpoints
) {
}