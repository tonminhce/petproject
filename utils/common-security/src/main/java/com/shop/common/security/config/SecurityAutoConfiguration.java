package com.shop.common.security.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import com.shop.common.security.service.ServiceCallerGuard;

@AutoConfiguration
@ConditionalOnClass({HttpSecurity.class, JwtDecoder.class, SecurityFilterChain.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "shop.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
@Import({BaseSecurityConfig.class, CorsAutoConfigurer.class})
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServiceCallerGuard serviceCallerGuard() {
        return new ServiceCallerGuard();
    }
}
