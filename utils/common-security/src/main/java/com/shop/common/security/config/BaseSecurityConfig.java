package com.shop.common.security.config;

import com.shop.common.security.jwt.JwtRolesConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

/**
 * Default {@link SecurityFilterChain} for every resource server in the platform.
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>CSRF disabled (stateless JWT auth)</li>
 *   <li>{@code SessionCreationPolicy.STATELESS}</li>
 *   <li>Public endpoints from {@link SecurityProperties#publicPaths()} plus
 *       {@link SecurityProperties.PlatformDefaults#PUBLIC_PATHS} permitted</li>
 *   <li>Everything else requires authentication via OAuth2 resource server</li>
 *   <li>Authorities mapped via {@link JwtRolesConverter}</li>
 *   <li>CORS enabled when a {@link CorsConfigurationSource} bean is available</li>
 * </ul>
 *
 * <p>Services that need custom rules (e.g. method-level scope checks) declare
 * their own {@code SecurityFilterChain} bean — the
 * {@code @ConditionalOnMissingBean} ensures this one steps aside.</p>
 */
@Configuration(proxyBeanMethods = false)
public class BaseSecurityConfig {

    private final SecurityProperties properties;

    public BaseSecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<CorsConfigurationSource> corsConfigurationSource) throws Exception {

        if (properties.csrfDisabled()) {
            http.csrf(AbstractHttpConfigurer::disable);
        }
        if (properties.statelessSession()) {
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        }

        var corsSource = corsConfigurationSource.getIfAvailable();
        if (corsSource != null) {
            http.cors(c -> c.configurationSource(corsSource));
        } else {
            http.cors(AbstractHttpConfigurer::disable);
        }

        http.authorizeHttpRequests(auth -> {
                    for (SecurityProperties.EndpointRule rule : properties.publicPaths()) {
                        if (rule.method() != null) {
                            auth.requestMatchers(rule.method(), rule.path()).permitAll();
                        } else {
                            auth.requestMatchers(rule.path()).permitAll();
                        }
                    }
                    // Platform defaults (actuator, swagger, api-docs) are always public — do NOT drop
                    auth.requestMatchers(SecurityProperties.PlatformDefaults.PUBLIC_PATHS.toArray(new String[0]))
                            .permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Build a {@link JwtDecoder} from the Keycloak realm's JWK set when no
     * decoder bean is already in the context. Spring Boot's auto-configuration
     * normally does this from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri},
     * but exposing it here keeps the module self-contained when a service
     * configures only {@code shop.security.issuer-uri}.
     *
     * <p>C1 fix — the validator chain now asserts (in order): issuer matches
     * {@link SecurityProperties#issuerUri()}, exp/nbf timestamps are valid,
     * and — if {@code shop.security.expected-audiences} is set — the {@code aud}
     * claim intersects that list. Without the aud check a token issued for
     * client A would be honoured by service B (cross-client horizontal
     * privilege escalation). Empty list keeps the legacy/dev posture of no
     * aud enforcement.</p>
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
        decoder.setJwtValidator(buildValidatorChain());
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> buildValidatorChain() {
        var audienceClaim = new JwtClaimValidator<List<String>>("aud", aud -> {
            if (properties.expectedAudiences().isEmpty()) {
                return true;
            }
            if (aud == null || aud.isEmpty()) {
                return false;
            }
            for (String expected : properties.expectedAudiences()) {
                if (aud.contains(expected)) {
                    return true;
                }
            }
            return false;
        });
        return new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuerUri()),
                new JwtTimestampValidator(),
                audienceClaim
        );
    }

    /**
     * Map Keycloak realm roles to Spring authorities. Wired only when the
     * service does not define its own converter (e.g. for scope-based authz).
     */
    @Bean
    @ConditionalOnMissingBean(JwtAuthenticationConverter.class)
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRolesConverter());
        return converter;
    }
}
