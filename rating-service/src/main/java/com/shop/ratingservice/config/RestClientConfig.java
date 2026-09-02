package com.shop.ratingservice.config;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * P0-4 — NO {@code @Qualifier} on {@code @Bean} params. Lombok does not copy
 * {@code @Qualifier} from fields to constructor params. Defining the bean as a
 * method signature here works because Spring wires by parameter NAME
 * ({@code orderRestClient}) — clients inject by name with
 * {@code @Qualifier("orderRestClient")}.
 *
 * <p>P1-3 — no auth header is set HERE (shared builder). Authorization is
 * attached PER-CALL inside {@code EligibilityClient} — the verify-purchase
 * endpoint is SERVICE-role and the header value comes from
 * {@code ServiceTokenProvider.getToken()}.</p>
 */
@Configuration
public class RestClientConfig {

    @Bean("orderRestClient")
    public RestClient orderRestClient(RatingClientProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.orderService().url(), props.orderService().timeoutMs(), traceparent);
    }

    private RestClient baseRestClient(String baseUrl, long timeoutMs, TraceparentInterceptor traceparent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // propagate the correlation id to the downstream order-service call.
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
    // A14: bare builder had no timeout → a hung Keycloak token endpoint would hold
    // the caller's HTTP thread forever (thread starvation under load). 3s connect
    // + 3s read mirrors the rest of the fleet's external clients.
    public RestClient.Builder restClientBuilder(TraceparentInterceptor traceparent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(3000).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(3000).toMillis());
        return RestClient.builder()
            .requestFactory(factory)
            .requestInterceptor(traceparent);
    }
}
