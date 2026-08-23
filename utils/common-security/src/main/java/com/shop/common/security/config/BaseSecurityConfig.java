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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Default {@link SecurityFilterChain} for every resource server in the platform.
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>CSRF disabled (stateless JWT auth)</li>
 *   <li>{@code SessionCreationPolicy.STATELESS}</li>
 *   <li>{@link SecurityProperties#resolvedPublicPaths()} permitted</li>
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

        String[] publicPaths = properties.resolvedPublicPaths().toArray(new String[0]);

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicPaths).permitAll()
                        .anyRequest().authenticated())
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
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
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
