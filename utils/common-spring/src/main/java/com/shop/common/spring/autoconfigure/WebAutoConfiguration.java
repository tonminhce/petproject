package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.common.spring.web.filter.CorrelationIdFilter;
import com.shop.common.spring.web.filter.HttpLogProperties;
import com.shop.common.spring.web.filter.HttpLoggingFilter;
import jakarta.servlet.Servlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires every web-tier cross-cutter for servlet-stack services:
 * correlation-id filter, HTTP logging filter, and the global exception handler.
 *
 * <p>Skipped automatically for reactive runtimes because of
 * {@code @ConditionalOnWebApplication(type = SERVLET)}.</p>
 */
@AutoConfiguration(after = I18nAutoConfiguration.class)
@ConditionalOnClass({Servlet.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(HttpLogProperties.class)
public class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "shop.web.correlation", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean(HttpLoggingFilter.class)
    @ConditionalOnProperty(prefix = "shop.web.logging.request", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public HttpLoggingFilter httpLoggingFilter(HttpLogProperties props) {
        return new HttpLoggingFilter(props);
    }

    @Bean
    @ConditionalOnMissingBean(ApiExceptionHandler.class)
    @ConditionalOnProperty(prefix = "shop.web.exception-handler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }
}