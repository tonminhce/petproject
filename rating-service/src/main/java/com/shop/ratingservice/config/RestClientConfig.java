package com.shop.ratingservice.config;

import com.shop.common.core.constants.MdcKey;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
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
 *
 * <p>R1 — traceparent propagation is central: the {@code RestClient.Builder}
 * bean is enriched by common-spring's
 * {@code traceparentRestClientBuilderPostProcessor}, so clients are built FROM
 * that bean and no interceptor is hand-added here.</p>
 */
@Configuration
public class RestClientConfig {

    @Bean("orderRestClient")
    public RestClient orderRestClient(RatingClientProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.orderService().url(), props.orderService().timeoutMs());
    }

    private RestClient baseRestClient(RestClient.Builder builder, String baseUrl, long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return builder
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // propagate the correlation id to the downstream order-service call.
            .requestInitializer(req -> {
                String corrId = MDC.get(MdcKey.CORRELATION_ID);
                if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
            })
            .build();
    }

    /**
     * Spring Boot 4 does not auto-register {@code RestClient.Builder} as a bean;
     * ServiceTokenProvider needs it. R1 — prototype scope so every consumer
     * gets a FRESH builder that the common-spring BPP has already enriched
     * with the traceparent interceptor; a shared mutable builder would
     * accumulate per-client config.
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
