package com.shop.shippingservice.carrier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarrierRestClientCustomizerTest {

    private static final long SERVER_STALL_MILLIS = 10_000L;
    private static final long BUDGET_MILLIS = 5_000L;

    @Test
    void theConfiguredBudgetIsExposedForTheCarrierCall() {
        CarrierRestClientCustomizer customizer = new CarrierRestClientCustomizer(2000, 5000);

        assertThat(customizer.connectTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(customizer.readTimeout()).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void aStalledCarrierIsAbandonedInsideTheBudget() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/shipments", exchange -> {
            try {
                Thread.sleep(SERVER_STALL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            CarrierRestClientCustomizer customizer = new CarrierRestClientCustomizer(1000, 300);
            RestClient.Builder builder = RestClient.builder();
            customizer.customize(builder);
            CarrierShipmentClient client = new CarrierShipmentClient(builder, new ObjectMapper());
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/shipments";

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> client.createShipment("GHN", endpoint, "token", "shop-1",
                    UUID.randomUUID(), "data.tracking_number"))
                    .isInstanceOf(BusinessException.class);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(elapsedMillis)
                    .as("the carrier call must fail inside the configured budget")
                    .isLessThan(BUDGET_MILLIS);
        } finally {
            server.stop(0);
        }
    }
}
