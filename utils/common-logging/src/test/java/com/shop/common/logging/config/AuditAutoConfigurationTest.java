package com.shop.common.logging.config;

import com.shop.common.logging.aspect.AuditAspect;
import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.audit.BoundedAsyncAuditEventWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional wiring for the audit stack: both beans by default, nothing when
 * {@code shop.audit.enabled=false}, and a user-supplied writer wins over the
 * default bounded one.
 */
class AuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    @Test
    void registersAspectAndBoundedWriterByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditAspect.class);
            assertThat(ctx).hasSingleBean(AuditEventWriter.class);
            assertThat(ctx.getBean(AuditEventWriter.class))
                    .isInstanceOf(BoundedAsyncAuditEventWriter.class);
        });
    }

    @Test
    void disabledPropertyRemovesAllAuditBeans() {
        runner.withPropertyValues("shop.audit.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(AuditAspect.class);
            assertThat(ctx).doesNotHaveBean(AuditEventWriter.class);
        });
    }

    @Test
    void userSuppliedWriterBacksOffTheDefault() {
        runner.withUserConfiguration(CustomWriterConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditEventWriter.class);
            assertThat(ctx).hasSingleBean(AuditAspect.class);
            assertThat(ctx.getBean(AuditEventWriter.class)).isSameAs(ctx.getBean("testWriter"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomWriterConfig {

        @Bean(destroyMethod = "")
        AuditEventWriter testWriter() {
            return new AuditEventWriter() {
                @Override
                public void write(AuditEvent event) {
                }

                @Override
                public long discardedEvents() {
                    return 0;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
