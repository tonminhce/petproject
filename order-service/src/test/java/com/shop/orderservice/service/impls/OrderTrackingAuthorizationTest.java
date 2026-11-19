package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.service.IdempotencyService;
import com.shop.orderservice.service.OrderCommitCoordinator;
import com.shop.orderservice.service.OrderConfirmMetrics;
import com.shop.orderservice.service.OrderEventPublisher;
import com.shop.orderservice.service.OrderStatusService;
import com.shop.orderservice.service.StockReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate of the unauthenticated order-tracking endpoint.
 *
 * <p>The tracking token is a random capability issued with the order, so it
 * authorises the lookup on its own. The phone number stays as the legacy
 * secondary factor, but it is a weak one - low entropy, often known to third
 * parties, and the order id leaks routinely - so that path counts the wrong
 * numbers and locks the order out of it after a few failures.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderTrackingAuthorizationTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock com.shop.orderservice.client.PromotionServiceClient promotionClient;
    @Mock com.shop.orderservice.client.PaymentServiceClient paymentClient;
    @Mock StockReservationService stockReservationService;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock IdempotencyService idempotencyService;
    @Mock OrderStatusService orderStatusService;
    @Mock OrderMapper orderMapper;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock OrderCommitCoordinator commitCoordinator;
    @Mock OrderConfirmMetrics confirmMetrics;
    @Mock OrderCreateSaga orderCreateSaga;

    @InjectMocks OrderServiceImpl service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID trackingCode = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // only the tests that reach the response need the line items
        lenient().when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());
    }

    private Order order() {
        return Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID())
                .status(OrderStatus.CONFIRMED)
                .subtotal(BigDecimal.valueOf(100))
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .total(BigDecimal.valueOf(100))
                .recipientName("Alice")
                .phoneNumber("0912345678")
                .shippingAddress("123 Main St")
                .trackingCode(trackingCode)
                .build();
    }

    @Test
    void theTrackingTokenAloneAuthorisesTheLookup() {
        Order order = order();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        var response = service.trackOrder(orderId, trackingCode.toString(), null);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.recipientName()).isEqualTo("Alice");
        // no failure bookkeeping on the phone path
        verify(orderRepository, never()).save(any());
    }

    @Test
    void aWrongTrackingTokenIsRefusedWithTheSameErrorAsAWrongPhone() {
        Order order = order();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.trackOrder(orderId, UUID.randomUUID().toString(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    void theCorrectPhoneStillWorks() {
        Order order = order();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        var response = service.trackOrder(orderId, null, "0912345678");

        assertThat(response.orderId()).isEqualTo(orderId);
    }

    @Test
    void aWrongPhoneIsCounted() {
        Order order = order();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.trackOrder(orderId, null, "0000000000"))
                .isInstanceOf(BusinessException.class);

        assertThat(order.getTrackFailures()).isEqualTo(1);
        verify(orderRepository).save(order);
    }

    @Test
    void thePhonePathLocksOutAfterTooManyWrongNumbers() {
        Order order = order();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        for (int attempt = 0; attempt < OrderServiceImpl.MAX_TRACK_PHONE_FAILURES; attempt++) {
            assertThatThrownBy(() -> service.trackOrder(orderId, null, "0000000000"))
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(order.getTrackLockedUntil())
                .as("the phone path is locked out after the failure budget")
                .isNotNull()
                .isAfter(Instant.now());

        // and the lock actually holds: the correct phone is refused too
        assertThatThrownBy(() -> service.trackOrder(orderId, null, "0912345678"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void theTrackingTokenStillWorksWhileThePhonePathIsLocked() {
        Order order = order();
        order.setTrackLockedUntil(Instant.now().plusSeconds(600));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        var response = service.trackOrder(orderId, trackingCode.toString(), null);

        assertThat(response.orderId()).isEqualTo(orderId);
    }

    @Test
    void aSuccessfulPhoneLookupClearsTheFailureCounter() {
        Order order = order();
        order.setTrackFailures(3);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.trackOrder(orderId, null, "0912345678");

        assertThat(order.getTrackFailures()).isZero();
        assertThat(order.getTrackLockedUntil()).isNull();
        verify(orderRepository).save(order);
    }
}
