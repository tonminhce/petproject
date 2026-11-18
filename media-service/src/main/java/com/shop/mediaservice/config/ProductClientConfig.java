package com.shop.mediaservice.config;

import com.shop.common.core.constants.MdcKey;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProductClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ProductClientProperties props, RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactory factory = createPooledRequestFactory((int) props.timeoutMs());

        return restClientBuilder
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            .requestInitializer(req -> {
                String corrId = MDC.get(MdcKey.CORRELATION_ID);
                if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
            })
            .build();
    }

    private ClientHttpRequestFactory createPooledRequestFactory(int timeoutMs) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(200)
            .setMaxConnPerRoute(50)
            .build();
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(timeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMs))
            .build();
        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictExpiredConnections()
            .evictIdleConnections(TimeValue.ofSeconds(30))
            .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
