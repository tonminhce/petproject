package com.shop.common.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class BaseSecurityConfigJwtDecoderLazyTest {

    @Test
    void jwtDecoderBeanFactoryDoesNotBlockWhenIssuerIsUnreachable() {

        String unreachable = "http://127.0.0.1:1/realms/ecommerce";
        SecurityProperties props = new SecurityProperties(
                true, unreachable, true, true,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                new SecurityProperties.Cors(true, java.util.List.of("*"), java.util.List.of(),
                        java.util.List.of("*"), java.util.List.of(), false, 3600L));

        BaseSecurityConfig config = new BaseSecurityConfig(props);

        long start = System.currentTimeMillis();
        JwtDecoder decoder = config.jwtDecoder();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(decoder).isNotNull();

        assertThat(elapsed)
                .as("bean factory call must be non-blocking")
                .isLessThan(2_000L);
    }

    @Test
    void applicationContextStartsWithoutDecoderInitThrowing() {

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

                });
    }
}
