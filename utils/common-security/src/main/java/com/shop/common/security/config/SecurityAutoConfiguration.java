package com.shop.common.security.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Autoconfigures the resource-server security stack for every servlet service
 * that has {@code spring-security} on the classpath.
 *
 * <p>{@code @Import(BaseSecurityConfig.class)} is intentional — it tells Spring
 * to process {@code BaseSecurityConfig} as a {@code @Configuration}, so its
 * {@code @Bean} methods ({@code securityFilterChain}, {@code jwtDecoder},
 * {@code jwtAuthenticationConverter}) are discovered and registered.</p>
 *
 * <p>Disable by setting {@code shop.security.enabled=false}.</p>
 */
@AutoConfiguration
@ConditionalOnClass({HttpSecurity.class, JwtDecoder.class, SecurityFilterChain.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "shop.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
@Import({BaseSecurityConfig.class, CorsAutoConfigurer.class})
public class SecurityAutoConfiguration {
}
