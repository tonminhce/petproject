package com.shop.common.spring.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Registers JPA auditing ({@code @CreatedDate}, {@code @LastModifiedDate},
 * {@code @CreatedBy}) plus the fleet's {@code AuditorAware} for every
 * context that uses JPA entities.
 *
 * <p>The {@code @EnableJpaAuditing} handler needs a real JPA runtime: it
 * registers {@code jpaAuditingHandler} + {@code jpaMappingContext} eagerly,
 * and {@code jpaMappingContext} throws {@code IllegalArgumentException:
 * "JPA metamodel must not be empty"} when no {@code EntityManagerFactory}
 * exists. Contexts that consume this starter <b>without</b> a datasource
 * (e.g. {@code CommonLibraryStarterTests}, which excludes
 * DataSource/Hibernate/JPA auto-configuration) must therefore opt out.
 *
 * <p><b>Why a property, not {@code @ConditionalOnBean(EntityManagerFactory)}?</b>
 * Bean-condition evaluation differs by how this class reaches a context, and
 * only the property is deterministic in both:
 * <ul>
 *   <li><b>{@code @Import} path</b> — fleet slices/tests import this class as a
 *       plain configuration: conditions are evaluated while configuration
 *       classes are PARSED, before any {@code @Bean} method registers its bean
 *       definition, so the {@code EntityManagerFactory} is never visible at
 *       evaluation time. This was verified empirically: a class-level
 *       {@code @ConditionalOnBean(EntityManagerFactory)} silently disabled
 *       auditing across fleet integration tests, leaving {@code created_at}
 *       NULL.</li>
 *   <li><b>Auto-configuration path</b> — conditions run AFTER regular bean
 *       definitions register, so EMF visibility there is not a parse-time
 *       problem but an ORDERING problem: it would hold only for consumers whose
 *       JPA auto-configuration is registered before this one via
 *       {@code @AutoConfiguration(after=...)}, and silently flip for everyone
 *       else.</li>
 * </ul>
 * And no class-marker works either: {@code hibernate-core} is on
 * every consumer's classpath via {@code common-core}'s
 * {@code spring-boot-starter-data-jpa}.
 *
 * <p>So the opt-out is explicit: set {@code shop.jpa.auditing.enabled=false}
 * in any no-datasource context that inherits this starter. Every JPA
 * service keeps auditing with zero configuration, because
 * {@code matchIfMissing = true} preserves today's behavior.
 */
@AutoConfiguration
@ConditionalOnClass(AuditingEntityListener.class)
@ConditionalOnProperty(prefix = "shop.jpa.auditing", name = "enabled", havingValue = "true", matchIfMissing = true)
// @EnableJpaAuditing MUST be on the outer @AutoConfiguration class — when
// placed on a nested static @Configuration, Spring's auto-config scanner
// sometimes skips it (depends on bean creation order), and the auditing
// listener never registers. Symptom: created_at / updated_at columns stay
// NULL on insert, violating the NOT NULL constraint in Liquibase-managed
// schemas.
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
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
