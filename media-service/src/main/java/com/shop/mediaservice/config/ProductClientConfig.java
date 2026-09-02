package com.shop.mediaservice.config;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Product-service {@link RestClient} for the H-4 purge-gate reference-count
 * call. P0-4 — NO {@code @Qualifier} on {@code @Bean} params: Lombok does not
 * copy {@code @Qualifier} from fields to constructor params, so Spring wires
 * by parameter NAME here ({@code productRestClient}) and clients inject with
 * {@code @Qualifier("productRestClient")}.
 *
 * <p>P1-3 — no auth header is set HERE (shared builder). Authorization is
 * attached PER-CALL inside {@code MediaReferenceClient} — the header value
 * comes from {@code ServiceTokenProvider.getToken()}.</p>
 */
@Configuration
public class ProductClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ProductClientProperties props, TraceparentInterceptor traceparent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());

        return RestClient.builder()
            .baseUrl(props.baseUrl())
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
