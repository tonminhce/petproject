package com.shop.common.security.config;

import com.shop.common.security.service.ServiceCallerGuard;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@AutoConfiguration
@ConditionalOnClass({SecurityContextHolder.class, JwtAuthenticationToken.class})
public class ServiceCallerGuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServiceCallerGuard serviceCallerGuard() {
        return new ServiceCallerGuard();
    }
}
