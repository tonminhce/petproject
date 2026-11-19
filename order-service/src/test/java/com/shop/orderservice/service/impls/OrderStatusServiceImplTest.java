package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.service.OrderStatusService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class OrderStatusServiceImplTest {

    private final OrderStatusService service = new OrderStatusServiceImpl();

    static Stream<Arguments> transitions() {

        return Stream.of(

            Arguments.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, true),
            Arguments.of(OrderStatus.PENDING, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.PENDING, OrderStatus.SHIPPED, false),

            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, true),
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.PENDING, false),

            Arguments.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED, true),
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.PENDING, false),

            Arguments.of(OrderStatus.DELIVERED, OrderStatus.PENDING, false),
            Arguments.of(OrderStatus.DELIVERED, OrderStatus.SHIPPED, false),
            Arguments.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, false),

            Arguments.of(OrderStatus.CANCELLED, OrderStatus.PENDING, false),
            Arguments.of(OrderStatus.CANCELLED, OrderStatus.CONFIRMED, false),

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
