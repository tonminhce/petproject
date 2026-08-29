package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.service.OrderStatusService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Plain UNIT test (no Spring, no Testcontainers). Pure state-machine
 * logic — table-driven Map + set lookup. No need to spin up a DB container (~30s
 * saved per run).
 */
class OrderStatusServiceImplTest {

    private final OrderStatusService service = new OrderStatusServiceImpl();

    static Stream<Arguments> transitions() {
        // 5 statuses × 5 statuses = 25 cases; ~5 allowed + 20 rejected
        return Stream.of(
            // PENDING: CONFIRMED, CANCELLED
            Arguments.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, true),
            Arguments.of(OrderStatus.PENDING, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.PENDING, OrderStatus.SHIPPED, false),
            // CONFIRMED: SHIPPED, CANCELLED
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, true),
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.PENDING, false),
            // SHIPPED: DELIVERED only
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED, true),
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED, false),
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.PENDING, false),
            // DELIVERED: terminal
            Arguments.of(OrderStatus.DELIVERED, OrderStatus.PENDING, false),
            // CANCELLED: terminal
            Arguments.of(OrderStatus.CANCELLED, OrderStatus.PENDING, false),
            // Same-state: rejected (strict)
            Arguments.of(OrderStatus.PENDING, OrderStatus.PENDING, false)
        );
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void validateTransition(OrderStatus from, OrderStatus to, boolean allowed) {
        if (allowed) {
            assertThatCode(() -> service.validateTransition(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> service.validateTransition(from, to))
                .isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4004"));
        }
    }
}
