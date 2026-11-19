package com.shop.paymentservice.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentPspRestClientConfig {

    @Bean("momoRestClient")
    public RestClient momoRestClient(RestClient.Builder builder, PaymentMoMoProperties properties) {
        return pspRestClient(builder, properties.timeoutMs());
    }

    @Bean("vnpayRestClient")
    public RestClient vnpayRestClient(RestClient.Builder builder, PaymentVNPayProperties properties) {
        return pspRestClient(builder, properties.timeoutMs());
    }

    private RestClient pspRestClient(RestClient.Builder builder, long timeoutMs) {
        return builder
                .requestFactory(pooledRequestFactory((int) timeoutMs))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private ClientHttpRequestFactory pooledRequestFactory(int timeoutMs) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
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
}
