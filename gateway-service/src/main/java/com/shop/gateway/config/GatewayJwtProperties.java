package com.shop.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateway.security")
public record GatewayJwtProperties(
        @NotEmpty List<@NotBlank String> expectedClientIds,
        @DefaultValue List<String> expectedAudiences
) {

    public GatewayJwtProperties {
        if (expectedClientIds == null || expectedClientIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "gateway.security.expected-client-ids must not be empty: the edge accepts a token "
                    + "only when its azp claim is an allow-listed client. Configure "
                    + "GATEWAY_EXPECTED_CLIENT_IDS with the browser client id.");
        }
    }
}
