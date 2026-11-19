package com.shop.shippingservice.carrier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class CarrierRestClientCustomizer implements RestClientCustomizer {

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public CarrierRestClientCustomizer(
            @Value("${shop.shipping.carrier.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${shop.shipping.carrier.read-timeout-ms:5000}") long readTimeoutMs) {
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    @Override
    public void customize(RestClient.Builder builder) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        if (!connectTimeout.isZero() && !connectTimeout.isNegative()) {
            httpClientBuilder.connectTimeout(connectTimeout);
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
        if (!readTimeout.isZero() && !readTimeout.isNegative()) {
            requestFactory.setReadTimeout(readTimeout);
        }
        builder.requestFactory(requestFactory);
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }
}
