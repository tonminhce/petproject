package com.shop.common.logging.config;

import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.audit.BoundedAsyncAuditEventWriter;
import com.shop.common.logging.aspect.AuditAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerMapping;

@AutoConfiguration
@ConditionalOnClass({Aspect.class, SecurityContextHolder.class, Jwt.class,
        RequestContextHolder.class, HandlerMapping.class})
@ConditionalOnProperty(prefix = "shop.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditLogProperties.class)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditEventWriter.class)
    public AuditEventWriter auditEventWriter() {
        return BoundedAsyncAuditEventWriter.create();
    }

    @Bean
    @ConditionalOnMissingBean(AuditAspect.class)
    public AuditAspect auditAspect(AuditEventWriter writer) {
        return new AuditAspect(writer);
    }
}
