package com.shop.orderservice.config;

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

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.product().url(), props.product().timeoutMs());
    }

    @Bean("inventoryRestClient")
    public RestClient inventoryRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.inventory().url(), props.inventory().timeoutMs());
    }

    @Bean("taxRestClient")
    public RestClient taxRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.tax().url(), props.tax().timeoutMs());
    }

    @Bean("promotionRestClient")
    public RestClient promotionRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.promotion().url(), props.promotion().timeoutMs());
    }

    @Bean("paymentRestClient")
    public RestClient paymentRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.payment().url(), props.payment().timeoutMs());
    }

    @Bean("paymentRefundRestClient")
    public RestClient paymentRefundRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.payment().url(), props.payment().refundTimeout());
    }

    private RestClient baseRestClient(RestClient.Builder builder, String baseUrl, int timeoutMs) {
        ClientHttpRequestFactory factory = createPooledRequestFactory(timeoutMs);

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
