package com.shop.productservice.config;

import com.shop.common.core.constants.MdcKey;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The inventory-service client of the delete-time stock guard (F-060).
 *
 * <p>Mirrors {@link MediaClientConfig}: the client is built FROM the shared
 * {@code RestClient.Builder} bean, so common-spring's
 * {@code traceparentRestClientBuilderPostProcessor} has already enriched it
 * with the traceparent interceptor. The Authorization header is attached
 * PER CALL inside the client (see
 * {@code InventoryReservationClient}) — never here.</p>
 */
@Configuration
public class InventoryClientConfig {

    @Bean("inventoryRestClient")
    public RestClient inventoryRestClient(InventoryClientProperties props,
                                          RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());

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
}
