package com.shop.mediaservice.config;

import com.shop.common.core.constants.MdcKey;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
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
 *
 * <p>R1 — traceparent propagation is central: the {@code RestClient.Builder}
 * bean is enriched by common-spring's
 * {@code traceparentRestClientBuilderPostProcessor}, so clients are built FROM
 * that bean and no interceptor is hand-added here.</p>
 */
@Configuration
public class ProductClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ProductClientProperties props, RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeoutMs());
        factory.setReadTimeout(props.timeoutMs());

        return restClientBuilder
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // propagate the correlation id to the downstream product-service call.
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
