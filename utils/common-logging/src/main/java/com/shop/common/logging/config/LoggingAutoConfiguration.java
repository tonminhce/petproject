package com.shop.common.logging.config;

import com.shop.common.logging.aspect.LoggerAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(Aspect.class)
@ConditionalOnProperty(prefix = "shop.web.logging.performance", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PerformanceLogProperties.class)
public class LoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LoggerAspect.class)
    public LoggerAspect loggerAspect(PerformanceLogProperties props) {
        return new LoggerAspect(props);
    }
}
