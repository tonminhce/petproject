package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.security.AudienceBoundary;
import com.shop.common.spring.security.AudienceBoundaryEnvironmentCustomiser;
import com.shop.common.spring.security.AudienceProperties;
import com.shop.common.spring.security.AudienceSecurityProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties({AudienceProperties.class, AudienceSecurityProperties.class})
public class AudienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public static AudienceBoundaryEnvironmentCustomiser audienceBoundaryEnvironmentCustomiser() {
        return new AudienceBoundaryEnvironmentCustomiser();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "shop.security", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public AudienceBoundary audienceBoundary(AudienceSecurityProperties security) {
        return new AudienceBoundary(security.expectedClientIds(), List.of());
    }
}
