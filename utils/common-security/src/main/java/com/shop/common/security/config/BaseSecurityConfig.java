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

import java.util.ArrayList;
import java.util.List;

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

                    auth.requestMatchers(SecurityProperties.PlatformDefaults.PUBLIC_PATHS.toArray(new String[0]))
                            .permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder() {
        OAuth2TokenValidator<Jwt> validator = buildValidatorChain();
        return new LazyJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }

    private OAuth2TokenValidator<Jwt> buildValidatorChain() {
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(new JwtIssuerValidator(properties.issuerUri()));
        validators.add(new JwtTimestampValidator());
        if (!properties.expectedAudiences().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("aud", aud -> {
                if (!(aud instanceof List<?> audiences) || audiences.isEmpty()) {
                    return false;
                }
                return audiences.stream().allMatch(String.class::isInstance)
                        && properties.expectedAudiences().stream().anyMatch(audiences::contains);
            }));
        }
        if (!properties.expectedClientIds().isEmpty()) {

            validators.add(new JwtClaimValidator<Object>("azp", azp -> {
                if (!(azp instanceof String clientId) || clientId.isBlank()) {
                    return false;
                }
                return properties.expectedClientIds().contains(clientId);
            }));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    @Bean
    @ConditionalOnMissingBean(JwtAuthenticationConverter.class)
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRolesConverter());
        return converter;
    }
}
