package com.shop.common.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Builds the {@link CorsConfigurationSource} bean used by Spring Security's
 * filter chain.
 *
 * <p>Driven entirely by {@link SecurityProperties.Cors} so a service can tune
 * CORS per environment without recompiling. When {@code shop.security.cors.enabled}
 * is {@code false} the bean is omitted and Spring Security falls back to its
 * built-in CORS handling.</p>
 *
 * <p>The {@code allowedOriginPatterns} field (not {@code allowedOrigins}) is
 * used intentionally — it is the only knob that supports {@code "*"} together
 * with {@code allowCredentials=true}, which is what most SPA backends need
 * during local development.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.security.cors", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CorsAutoConfigurer {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        var corsProps = properties.cors();
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfiguration(corsProps));
        return source;
    }

    private static final Logger log = LoggerFactory.getLogger(CorsAutoConfigurer.class);

    private static CorsConfiguration buildConfiguration(SecurityProperties.Cors props) {
        if (Boolean.TRUE.equals(props.allowCredentials()) && props.resolvedAllowedOriginPatterns().contains("*")) {
            log.warn("CORS configured with allowCredentials=true and wildcard '*' origin pattern. "
                    + "This allows any origin to send credentialed cross-origin requests.");
        }
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(unwrap(props.resolvedAllowedOriginPatterns()));
        config.setAllowedMethods(unwrap(props.resolvedAllowedMethods()));
        config.setAllowedHeaders(unwrap(props.resolvedAllowedHeaders()));
        config.setExposedHeaders(unwrap(nullToEmpty(props.exposedHeaders())));
        config.setAllowCredentials(props.allowCredentials());
        config.setMaxAge(props.maxAgeSeconds());
        // Spring Security picks up the source only when wired via http.cors(Customizer.withDefaults())
        // — that happens in BaseSecurityConfig.
        return config;
    }

    private static List<String> unwrap(List<String> values) {
        return List.copyOf(values);
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** Shared constant — kept here so callers can pass {@code Customizer.withDefaults()}. */
    public static final Customizer<Void> NOOP = Customizer.withDefaults();
}
