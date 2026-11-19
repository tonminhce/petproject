package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The abuse brake of the unauthenticated order-tracking endpoint, against the
 * REAL database.
 *
 * <p>The unit test of the service uses a mocked repository, so it can only
 * prove that the counter is written on the entity. It cannot prove that the
 * counter SURVIVES: the rejection is a {@link BusinessException} (a
 * {@link RuntimeException}), so a plain {@code @Transactional} method rolls the
 * bookkeeping back together with the error and the brake never engages. That
 * is the defect this IT pins down.</p>
 */
class OrderTrackingAbuseBrakeIT extends AbstractOrderServiceIT {

    private static final String PHONE = "0912345678";

    private UUID orderId;
    private UUID trackingCode;

    @BeforeEach
    void seedOrder() {
        trackingCode = UUID.randomUUID();
        Order order = Order.builder()
                .userId(UUID.randomUUID())
                .status(OrderStatus.CONFIRMED)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .total(new BigDecimal("100.00"))
                .recipientName("Alice")
                .phoneNumber(PHONE)
                .shippingAddress("123 Main St")
                .trackingCode(trackingCode)
                .build();
        Order saved = orderRepository.saveAndFlush(order);
        orderId = saved.getId();
        assertThat(orderId).as("the persisted order must carry its generated id").isNotNull();
    }

    @Test
    void aWrongPhoneIsCountedInTheDatabase() {
        assertThatThrownBy(() -> orderService.trackOrder(orderId, null, "0000000000"))
                .isInstanceOf(BusinessException.class);

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getTrackFailures())
                .as("the failure counter must survive the rejection that recorded it")
                .isEqualTo(1);
    }

    @Test
    void thePhonePathLocksOutAfterTheFailureBudget() {
        for (int attempt = 0; attempt < OrderServiceImpl.MAX_TRACK_PHONE_FAILURES; attempt++) {
            assertThatThrownBy(() -> orderService.trackOrder(orderId, null, "0000000000"))
                    .isInstanceOf(BusinessException.class);
        }

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getTrackLockedUntil())
                .as("the phone path must be locked out after the failure budget")
                .isNotNull()
                .isAfter(Instant.now());

        // and the lock holds even for the correct phone
        assertThatThrownBy(() -> orderService.trackOrder(orderId, null, PHONE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aCorrectPhoneClearsTheCounter() {
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> orderService.trackOrder(orderId, null, "0000000000"))
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(orderService.trackOrder(orderId, null, PHONE).orderId()).isEqualTo(orderId);

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getTrackFailures()).isZero();
        assertThat(persisted.getTrackLockedUntil()).isNull();
    }

    @Test
    void theTrackingTokenAloneAuthorisesTheLookup() {
        var response = orderService.trackOrder(orderId, trackingCode.toString(), null);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.recipientName()).isEqualTo("Alice");

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getTrackFailures())
                .as("the token path must not touch the phone-path bookkeeping")
                .isZero();
    }

    @Test
    void theTrackingTokenStillWorksWhileThePhonePathIsLocked() {
        for (int attempt = 0; attempt < OrderServiceImpl.MAX_TRACK_PHONE_FAILURES; attempt++) {
            assertThatThrownBy(() -> orderService.trackOrder(orderId, null, "0000000000"))
                    .isInstanceOf(BusinessException.class);
        }

        var response = orderService.trackOrder(orderId, trackingCode.toString(), null);

        assertThat(response.orderId()).isEqualTo(orderId);
    }
}
