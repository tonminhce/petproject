package com.shop.orderservice.config;

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
 * {@code @Qualifier} from fields to constructor params. Defining beans as method
 * signatures here works because Spring wires by parameter NAME ({@code productRestClient},
 * {@code inventoryRestClient}, etc.) — clients inject by name with
 * {@code @Qualifier("productRestClient")}.
 *
 * <p>P1-3 — no auth header is set HERE (shared builders). Authorization is attached
 * PER-CALL inside each client method — only {@code InventoryServiceClient} needs a
 * token (product GET is a public path; tax/promotion are disabled in the MVP). The
 * header value comes from {@code ServiceTokenProvider.getToken()}.</p>
 *
 * <p>R1 — traceparent propagation is central: every {@code RestClient.Builder}
 * bean is enriched by common-spring's
 * {@code traceparentRestClientBuilderPostProcessor}, so clients are built FROM
 * that bean and no interceptor is hand-added here.</p>
 */
@Configuration
public class RestClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.product().url(), props.product().timeoutMs());
    }

    @Bean("inventoryRestClient")
    public RestClient inventoryRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.inventory().url(), props.inventory().timeoutMs());
    }

    @Bean("taxRestClient")
    public RestClient taxRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.tax().url(), props.tax().timeoutMs());
    }

    @Bean("promotionRestClient")
    public RestClient promotionRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.promotion().url(), props.promotion().timeoutMs());
    }

    @Bean("paymentRestClient")
    public RestClient paymentRestClient(ShopServicesProperties props, RestClient.Builder restClientBuilder) {
        return baseRestClient(restClientBuilder, props.payment().url(), props.payment().timeoutMs());
    }

    private RestClient baseRestClient(RestClient.Builder builder, String baseUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return builder
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            // hardening §2/D9 — propagate the correlation id to every downstream
            // service call (product/inventory/tax/promotion all share this builder).
            .requestInitializer(req -> {
                String corrId = MDC.get(MdcKey.CORRELATION_ID);
                if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
            })
            .build();
    }

    /**
     * Spring Boot 4 does not auto-register {@code RestClient.Builder} as a bean;
     * ServiceTokenProvider needs it. R1 — prototype scope so every consumer
     * (each client bean above + ServiceTokenProvider) gets a FRESH builder that
     * the common-spring BPP has already enriched with the traceparent
     * interceptor; a shared mutable builder would accumulate per-client config.
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
