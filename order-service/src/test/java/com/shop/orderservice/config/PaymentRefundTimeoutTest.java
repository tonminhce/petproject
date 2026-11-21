package com.shop.orderservice.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRefundTimeoutTest {

    private static final long SERVER_DELAY_MILLIS = 1500L;

    @Test
    void theRefundTimeoutOutlastsTheTimeoutOfTheLookups() {
        ShopServicesProperties.Payment payment =
                new ShopServicesProperties.Payment("http://payment.test", 3000, true, null);

        assertThat(payment.refundTimeout())
                .as("the refund crosses the payment provider and must outlast the lookup timeout")
                .isGreaterThan(payment.timeoutMs());
        assertThat(payment.refundTimeout()).isGreaterThanOrEqualTo(45000);
    }

    @Test
    void theConfiguredRefundTimeoutIsUsed() {
        ShopServicesProperties.Payment payment =
                new ShopServicesProperties.Payment("http://payment.test", 3000, true, 90000);

        assertThat(payment.refundTimeout()).isEqualTo(90000);
    }

    @Test
    void theRefundHasItsOwnClientAndTheLookupKeepsTheShortTimeout() throws Exception {
        assertThat(RestClientConfig.class.getMethod("paymentRestClient",
                ShopServicesProperties.class, RestClient.Builder.class)
                .getAnnotation(Bean.class).value()[0]).isEqualTo("paymentRestClient");
        assertThat(RestClientConfig.class.getMethod("paymentRefundRestClient",
                ShopServicesProperties.class, RestClient.Builder.class)
                .getAnnotation(Bean.class).value()[0]).isEqualTo("paymentRefundRestClient");
    }

    @Test
    void theRefundClientWaitsForTheProviderWhileTheLookupClientGivesUp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(SERVER_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ShopServicesProperties props = new ShopServicesProperties(null, null, null, null,
                    new ShopServicesProperties.Payment("http://127.0.0.1:" + server.getAddress().getPort(),
                            500, true, 10000), null);
            RestClient lookup = new RestClientConfig().paymentRestClient(props, RestClient.builder());
            RestClient refund = new RestClientConfig().paymentRefundRestClient(props, RestClient.builder());

            assertThatThrownBy(() -> lookup.get().uri("/slow").retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(refund.get().uri("/slow").retrieve().toBodilessEntity()).isNotNull();
        } finally {
            server.stop(0);
        }
    }
}
