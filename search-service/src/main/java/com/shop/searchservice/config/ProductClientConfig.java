package com.shop.searchservice.config;

import com.shop.common.core.constants.MdcKey;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ProductClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.product().url(), props.product().timeoutMs());
    }

    private RestClient baseRestClient(RestClient.Builder builder, String baseUrl, long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return builder
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            .requestInitializer(req -> {
                String corrId = MDC.get(MdcKey.CORRELATION_ID);
                if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
            })
            .build();
    }

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
