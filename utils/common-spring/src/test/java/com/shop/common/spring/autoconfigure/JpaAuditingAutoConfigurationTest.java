package com.shop.common.spring.autoconfigure;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaAuditingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JpaAuditingAutoConfiguration.class))
        .withBean(EntityManagerFactory.class, this::stubEntityManagerFactory);

    private EntityManagerFactory stubEntityManagerFactory() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        Metamodel metamodel = mock(Metamodel.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        EntityType entityType = mock(EntityType.class);
        when(emf.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(entityType));
        return emf;
    }

    @Test
    void registersAuditorAwareBean() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditorAware.class);
            assertThat(ctx.containsBean("jpaAuditingHandler"))
                .as("JPA auditing infrastructure must be registered when an EntityManagerFactory is present")
                .isTrue();
        });
    }

    @Test
    void doesNotRegisterAuditingWhenOptedOut() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaAuditingAutoConfiguration.class))
            .withPropertyValues("shop.jpa.auditing.enabled=false")
            .run(ctx -> {
                assertThat(ctx).doesNotHaveBean(AuditorAware.class);
                assertThat(ctx.containsBean("jpaAuditingHandler"))
                    .as("No auditing infrastructure must exist in a no-JPA context")
                    .isFalse();
            });
    }

    @Test
    void auditorReturnsUsernameWhenAuthenticated() {
        contextRunner.run(ctx -> {
            AuditorAware<String> auditor = ctx.getBean(AuditorAware.class);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            assertThat(auditor.getCurrentAuditor()).contains("alice");
            SecurityContextHolder.clearContext();
        });
    }

    @Test
    void auditorReturnsSystemWhenAnonymous() {
        contextRunner.run(ctx -> {
            AuditorAware<String> auditor = ctx.getBean(AuditorAware.class);
            assertThat(auditor.getCurrentAuditor()).contains("system");
        });
    }
}
