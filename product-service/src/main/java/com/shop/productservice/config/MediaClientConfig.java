package com.shop.productservice.config;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration
public class MediaClientConfig {

    @Bean("mediaRestClient")
    public RestClient mediaRestClient(MediaClientProperties props, TraceparentInterceptor traceparent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());

        return RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // propagate the correlation id to the downstream media-service call.
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
