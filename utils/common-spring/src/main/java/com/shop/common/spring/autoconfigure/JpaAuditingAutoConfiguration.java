package com.shop.common.spring.autoconfigure;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@AutoConfiguration
@ConditionalOnClass(AuditingEntityListener.class)
// @EnableJpaAuditing MUST be on the outer @AutoConfiguration class — when
// placed on a nested static @Configuration, Spring's auto-config scanner
// sometimes skips it (depends on bean creation order), and the auditing
// listener never registers. Symptom: created_at / updated_at columns stay
// NULL on insert, violating the NOT NULL constraint in Liquibase-managed
// schemas.
@org.springframework.data.jpa.repository.config.EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditorAware<String> auditorAware() {
        return () -> {
            // Note: SecurityContextHolder is ThreadLocal; for reactive (WebFlux) contexts
            // the lambda may run on a thread without a propagated SecurityContext, returning
            // "system" for authenticated requests. Override this bean with a
            // ReactiveSecurityContextHolder-aware AuditorAware in reactive services.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
                return Optional.of(auth.getName());
            }
            return Optional.of("system");
        };
    }
}
