package com.shop.orderservice.config;

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
 * {@code @Qualifier} from fields to constructor params. Defining beans as method
 * signatures here works because Spring wires by parameter NAME ({@code productRestClient},
 * {@code inventoryRestClient}, etc.) — clients inject by name with
 * {@code @Qualifier("productRestClient")}.
 *
 * <p>P1-3 — no auth header is set HERE (shared builders). Authorization is attached
 * PER-CALL inside each client method — only {@code InventoryServiceClient} needs a
 * token (product GET is a public path; tax/promotion are disabled in the MVP). The
 * header value comes from {@code ServiceTokenProvider.getToken()}.</p>
 */
@Configuration
public class RestClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.product().url(), props.product().timeoutMs(), traceparent);
    }

    @Bean("inventoryRestClient")
    public RestClient inventoryRestClient(ShopServicesProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.inventory().url(), props.inventory().timeoutMs(), traceparent);
    }

    @Bean("taxRestClient")
    public RestClient taxRestClient(ShopServicesProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.tax().url(), props.tax().timeoutMs(), traceparent);
    }

    @Bean("promotionRestClient")
    public RestClient promotionRestClient(ShopServicesProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.promotion().url(), props.promotion().timeoutMs(), traceparent);
    }

    @Bean("paymentRestClient")
    public RestClient paymentRestClient(ShopServicesProperties props, TraceparentInterceptor traceparent) {
        return baseRestClient(props.payment().url(), props.payment().timeoutMs(), traceparent);
    }

    private RestClient baseRestClient(String baseUrl, int timeoutMs, TraceparentInterceptor traceparent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // hardening §2/D9 — propagate the correlation id to every downstream
            // service call (product/inventory/tax/promotion all share this builder).
            .requestInitializer(req -> {
                String corrId = MDC.get(MdcKey.CORRELATION_ID);
                if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
            })
            // D3 — W3C traceparent propagation on every fleet outbound call.
            .requestInterceptor(traceparent)
            .build();
    }

    /** ponytail: Spring Boot 4 does not auto-register RestClient.Builder as a bean; ServiceTokenProvider needs it. */
    @Bean
    public RestClient.Builder restClientBuilder(TraceparentInterceptor traceparent) {
        return RestClient.builder().requestInterceptor(traceparent);
    }
}
