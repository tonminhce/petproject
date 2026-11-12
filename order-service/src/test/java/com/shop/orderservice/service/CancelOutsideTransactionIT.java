package com.shop.orderservice.service;

import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The cancel path must not hold a database connection across the remote
 * releases.
 *
 * <p>Each release is an HTTP call with a 3-5 second timeout; inside a
 * transaction every one of them kept a pooled connection busy. With a pool of
 * 20, a burst of cancellations against a degraded inventory or payment service
 * exhausted the pool and stalled unrelated requests of the same service.</p>
 *
 * <p>The pool here has ONE connection on purpose: with the cancel path
 * transactional, the remote call would hold it and the concurrent read below
 * would have to wait for the whole call to finish.</p>
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=1")
class CancelOutsideTransactionIT extends AbstractOrderServiceIT {

    private static final long REMOTE_CALL_MILLIS = 3000;

    @Test
    void theDatabaseIsFreeWhileTheRemoteReleaseRuns() throws Exception {
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/reservations/.*/release"))
                .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay((int) REMOTE_CALL_MILLIS)));

        UUID userId = UUID.randomUUID();
        UUID orderId;
        UUID reservationId = UUID.randomUUID();
        Order order = Order.builder()
                .userId(userId)
                .status(OrderStatus.PENDING)
                .subtotal(BigDecimal.TEN)
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .build();
        Order saved = orderRepository.saveAndFlush(order);
        orderId = saved.getId();
        orderItemRepository.saveAndFlush(OrderItem.builder()
                .orderId(orderId)
                .productId(UUID.randomUUID())
                .productTitle("Widget")
                .quantity(1)
                .unitPrice(BigDecimal.TEN)
                .lineTotal(BigDecimal.TEN)
                .reservationId(reservationId)
                .build());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancel = pool.submit(() -> orderService.cancelOrder(orderId, userId, false));

            // the remote call is in flight
            await().atMost(10, TimeUnit.SECONDS).until(() -> !inventoryServer
                    .findAll(postRequestedFor(urlMatching("/api/v1/inventory/reservations/.*/release")))
                    .isEmpty());

            // the only connection of the pool must NOT be held by the cancel
            long start = System.nanoTime();
            orderRepository.findById(orderId);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMillis)
                    .as("the cancel path must not hold the connection across the remote call")
                    .isLessThan(REMOTE_CALL_MILLIS / 2);

            cancel.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
