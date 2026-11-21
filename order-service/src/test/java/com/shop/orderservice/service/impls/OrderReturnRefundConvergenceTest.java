package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderReturn;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OrderReturnRepository;
import com.shop.orderservice.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class OrderReturnRefundConvergenceTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID RETURN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final String REFUNDABLE_URL =
            "http://payment.test/api/v1/payments/by-order/" + ORDER_ID + "/refundable?refundReference=" + RETURN_ID;
    private static final String REFUND_URL =
            "http://payment.test/api/v1/payments/" + PAYMENT_ID + "/refund";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderReturnRepository orderReturnRepository;

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer lookupServer;
    private MockRestServiceServer refundServer;
    private OrderReturnServiceImpl service;
    private Order order;

    @BeforeEach
    void setUp() {
        RestClient.Builder lookupBuilder = RestClient.builder().baseUrl("http://payment.test");
        lookupServer = MockRestServiceServer.bindTo(lookupBuilder).build();
        RestClient.Builder refundBuilder = RestClient.builder().baseUrl("http://payment.test");
        refundServer = MockRestServiceServer.bindTo(refundBuilder).build();
        ShopServicesProperties props = new ShopServicesProperties(null, null, null, null,
                new ShopServicesProperties.Payment("http://payment.test", 3000, true, 45000), null);
        when(serviceTokenProvider.getToken()).thenReturn("svc-token");
        service = new OrderReturnServiceImpl(orderRepository, orderReturnRepository,
                new PaymentServiceClient(props, lookupBuilder.build(), refundBuilder.build(), serviceTokenProvider));

        order = Order.builder()
                .id(ORDER_ID)
                .userId(UUID.randomUUID())
                .status(OrderStatus.DELIVERED)
                .total(new BigDecimal("2499.00"))
                .currency("VND")
                .build();
        when(orderReturnRepository.findOrderIdByReturnId(RETURN_ID)).thenAnswer(inv -> Optional.of(ORDER_ID));
        when(orderReturnRepository.findByOrderIdForUpdate(ORDER_ID)).thenAnswer(inv -> List.of(requestedReturn()));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderReturn requestedReturn() {
        return OrderReturn.builder()
                .id(RETURN_ID)
                .order(order)
                .userId(order.getUserId())
                .reason("Damaged on arrival")
                .refundAmount(new BigDecimal("2499.00"))
                .currency("VND")
                .status(ReturnStatus.REQUESTED)
                .build();
    }

    private static String capturedPaymentJson() {
        return "{\"success\":true,\"code\":\"OK\",\"data\":{\"id\":\"%s\",\"orderId\":\"%s\","
                .formatted(PAYMENT_ID, ORDER_ID)
                + "\"status\":\"CAPTURED\",\"amount\":2499.00,\"currency\":\"VND\"}}";
    }

    private static String refundedPaymentJson() {
        return "{\"success\":true,\"code\":\"OK\",\"data\":{\"id\":\"%s\",\"orderId\":\"%s\","
                .formatted(PAYMENT_ID, ORDER_ID)
                + "\"status\":\"REFUNDED\",\"amount\":2499.00,\"currency\":\"VND\","
                + "\"refundedAmount\":2499.00,\"refundedReference\":\"%s\"}}".formatted(RETURN_ID);
    }

    @Test
    void aReviewInterruptedByATimeoutConvergesOnTheRetryAndMovesNoAdditionalMoney() {
        lookupServer.expect(requestTo(REFUNDABLE_URL))
                .andRespond(withSuccess(capturedPaymentJson(), MediaType.APPLICATION_JSON));
        lookupServer.expect(requestTo(REFUNDABLE_URL))
                .andRespond(withSuccess(refundedPaymentJson(), MediaType.APPLICATION_JSON));
        refundServer.expect(requestTo(REFUND_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new IOException("read timed out")));

        OrderReturnReviewRequest review =
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager");

        assertThatThrownBy(() -> service.reviewReturn(RETURN_ID, "admin_user", review))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("ERR-0503"));
        verify(orderReturnRepository, never()).save(any(OrderReturn.class));

        OrderReturnResponse retried = service.reviewReturn(RETURN_ID, "admin_user", review);

        assertThat(retried.status()).isEqualTo(ReturnStatus.REFUNDED);
        ArgumentCaptor<OrderReturn> saved = ArgumentCaptor.forClass(OrderReturn.class);
        verify(orderReturnRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        lookupServer.verify();
        refundServer.verify();
    }
}
