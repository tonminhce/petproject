package com.shop.common.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H33 — the {@code JwtDecoder} bean MUST NOT block on OIDC / JWKS discovery
 * during bean creation. The previous implementation called
 * {@code NimbusJwtDecoder.withIssuerLocation(...).build()} directly inside the
 * {@code @Bean} factory, which performs a synchronous HTTP fetch of the OIDC
 * discovery document. That made startup (and the first request to a public
 * endpoint) wait on Keycloak being reachable — a blocker in dev / CI / multi-
 * pod rolling restarts.
 *
 * <p>The Holder-pattern fix moves discovery to first JWT decode. The unit
 * test below pins the contract that bean factory call returns within a
 * fraction of a second even when the issuer URL is unreachable.</p>
 */
class BaseSecurityConfigJwtDecoderLazyTest {

    @Test
    void jwtDecoderBeanFactoryDoesNotBlockWhenIssuerIsUnreachable() {
        // Pick an address the OS will reject immediately (connection refused),
        // so any synchronous HTTP attempt fails fast — proving the bean
        // creation did not perform one.
        String unreachable = "http://127.0.0.1:1/realms/ecommerce";
        SecurityProperties props = new SecurityProperties(
                true, unreachable, true, true,
                java.util.List.of(), java.util.List.of(),
                new SecurityProperties.Cors(true, java.util.List.of("*"), java.util.List.of(),
                        java.util.List.of("*"), java.util.List.of(), false, 3600L));

        BaseSecurityConfig config = new BaseSecurityConfig(props);

        long start = System.currentTimeMillis();
        JwtDecoder decoder = config.jwtDecoder();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(decoder).isNotNull();
        // Bean creation must complete well under any HTTP timeout — proving
        // no OIDC discovery was attempted eagerly.
        assertThat(elapsed)
                .as("bean factory call must be non-blocking")
                .isLessThan(2_000L);
    }

    @Test
    void applicationContextStartsWithoutDecoderInitThrowing() {
        // Same unreachable issuer; the test exercises a real Spring Boot web
        // context with the SecurityAutoConfiguration auto-configuration active.
        // If the JwtDecoder bean factory is still synchronous-on-startup, this
        // context will fail to start (or hang on bean init). After the fix it
        // starts cleanly because the bean is a Holder that defers discovery.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class,
                        org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class))
                .withPropertyValues(
                        "shop.security.issuer-uri=http://127.0.0.1:1/realms/ecommerce",
                        "shop.security.csrf-disabled=true",
                        "spring.main.web-application-type=servlet")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThat(decoder).isNotNull();
                    // The bean factory itself must not have hit the wire —
                    // the assertion is the context-load completing cleanly.
                });
    }
}