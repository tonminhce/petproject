package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.config.TrustedProxyProperties;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.common.spring.web.filter.CorrelationIdFilter;
import com.shop.common.spring.web.filter.HttpLogProperties;
import com.shop.common.spring.web.filter.HttpLoggingFilter;
import com.shop.common.spring.web.util.ClientIpTrustPostureValidator;
import com.shop.common.spring.web.util.TrustedProxyPolicy;
import jakarta.servlet.Servlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = I18nAutoConfiguration.class)
@ConditionalOnClass({Servlet.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({HttpLogProperties.class, TrustedProxyProperties.class})
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
    public HttpLoggingFilter httpLoggingFilter(HttpLogProperties props, TrustedProxyProperties trustedProxy) {
        return new HttpLoggingFilter(props,
                TrustedProxyPolicy.of(trustedProxy.cidrs(), trustedProxy.hops()));
    }

    @Bean
    @ConditionalOnMissingBean(ApiExceptionHandler.class)
    @ConditionalOnProperty(prefix = "shop.web.exception-handler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientIpTrustPostureValidator clientIpTrustPostureValidator(
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy,
            TrustedProxyProperties trustedProxy) {
        return new ClientIpTrustPostureValidator(forwardHeadersStrategy, trustedProxy);
    }
}