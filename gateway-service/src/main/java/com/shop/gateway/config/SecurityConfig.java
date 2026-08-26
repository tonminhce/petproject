package com.shop.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};
    private static final List<String> EXPOSED_HEADERS = List.of(
            "Authorization",
            "Content-Type",
            "X-Request-Id",
            "X-RateLimit-Remaining",
            "X-RateLimit-Replenish-Rate",
            "X-RateLimit-Burst-Capacity",
            "X-RateLimit-Requested-Tokens");
    private static final long MAX_AGE_SECONDS = 3600L;

    private final GatewayProperties properties;

    public SecurityConfig(final GatewayProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(final ServerHttpSecurity http) {
        var publicPathMatchers = new String[properties.publicEndpoints().size()];
        var publicPaths = properties.publicEndpoints().toArray(publicPathMatchers);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(publicPaths).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        var source = new UrlBasedCorsConfigurationSource();
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(properties.corsAllowedOriginPatterns());
        config.setAllowedMethods(List.of(HTTP_METHODS));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(MAX_AGE_SECONDS);
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
