package com.shop.productservice.config;

import com.shop.common.core.constants.MdcKey;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Media-service {@link RestClient} for the Option C write-time existence
 * check (media epic spec D5). P0-4 — NO {@code @Qualifier} on {@code @Bean}
 * params: Lombok does not copy {@code @Qualifier} from fields to constructor
 * params, so Spring wires by parameter NAME here ({@code mediaRestClient})
 * and clients inject with {@code @Qualifier("mediaRestClient")}.
 *
 * <p>P1-3 — no auth header is set HERE (shared builder). Authorization is
 * attached PER-CALL inside {@code MediaHeadClient} — the header value comes
 * from {@code ServiceTokenProvider.getToken()}.</p>
 *
 * <p>R1 — traceparent propagation is central: the {@code RestClient.Builder}
 * bean is enriched by common-spring's
 * {@code traceparentRestClientBuilderPostProcessor}, so clients are built FROM
 * that bean and no interceptor is hand-added here.</p>
 */
@Configuration
public class MediaClientConfig {

    @Bean("mediaRestClient")
    public RestClient mediaRestClient(MediaClientProperties props, RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());

        return restClientBuilder
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // propagate the correlation id to the downstream media-service call.
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
