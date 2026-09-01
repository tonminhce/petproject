package com.shop.searchservice.config;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Product-service {@link RestClient} for the reindex source stream (spec D5).
 * P0-4 — NO {@code @Qualifier} on {@code @Bean} params: Lombok does not copy
 * {@code @Qualifier} from fields to constructor params, so Spring wires by
 * parameter NAME here ({@code productRestClient}) and clients inject with
 * {@code @Qualifier("productRestClient")}.
 *
 * <p>P1-3 — no auth header is set HERE (shared builder). Authorization is
 * attached PER-CALL inside {@code ProductBackofficeClient}: the reindex stream
 * is consumed with the SERVICE client-credentials token from
 * {@code ServiceTokenProvider.getToken()}, and the product endpoint admits
 * hasAnyRole('SERVICE','ADMIN') (verify-purchase pattern).</p>
 */
@Configuration
public class ProductClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.product().url(), props.product().timeoutMs(), traceparent);
    }

    private RestClient baseRestClient(String baseUrl, long timeoutMs, TraceparentInterceptor traceparent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // propagate the correlation id to the downstream product-service call.
            .requestInitializer(req -> {
                String corrId = MDC.get(MdcKey.CORRELATION_ID);
                if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
            })
            // D3 — W3C traceparent propagation on every fleet outbound call.
            .requestInterceptor(traceparent)
            .build();
    }

    /** Spring Boot 4 does not auto-register RestClient.Builder as a bean; ServiceTokenProvider needs it. */
    @Bean
    public RestClient.Builder restClientBuilder(TraceparentInterceptor traceparent) {
        return RestClient.builder().requestInterceptor(traceparent);
    }
}
