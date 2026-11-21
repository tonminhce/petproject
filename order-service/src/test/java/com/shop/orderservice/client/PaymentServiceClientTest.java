package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PaymentStatusSnapshot;
import com.shop.orderservice.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class PaymentServiceClientTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID PAYMENT_CAPTURED = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID REFUND_REFERENCE = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final String CAPTURED_URL =
            "http://payment.test/api/v1/payments/by-order/" + ORDER_ID + "/captured";
    private static final String REFUNDABLE_URL =
            "http://payment.test/api/v1/payments/by-order/" + ORDER_ID + "/refundable";
    private static final String REFUND_URL =
            "http://payment.test/api/v1/payments/" + PAYMENT_CAPTURED + "/refund";
    private static final BigDecimal CAPTURED_AMOUNT = new BigDecimal("2499.00");

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private PaymentServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ShopServicesProperties props = new ShopServicesProperties(null, null, null, null,
            new ShopServicesProperties.Service("http://payment.test", 3000, true), null);
        client = new PaymentServiceClient(props, builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("svc-token");
    }

    private static String capturedJson() {
        return "{\"success\":true,\"code\":\"OK\",\"data\":{\"id\":\"%s\",\"orderId\":\"%s\",\"status\":\"CAPTURED\",\"amount\":2499.00,\"currency\":\"VND\"}}".formatted(PAYMENT_CAPTURED, ORDER_ID);
    }

    private static String partiallyRefundedJson() {
        return "{\"success\":true,\"code\":\"OK\",\"data\":{\"id\":\"%s\",\"orderId\":\"%s\",\"status\":\"PARTIALLY_REFUNDED\",\"amount\":2499.00,\"currency\":\"VND\",\"refundedAmount\":1000.00}}".formatted(PAYMENT_CAPTURED, ORDER_ID);
    }

    private static ResponseCreator errorResponse(HttpStatus status, String code) {
        return MockRestResponseCreators.withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"%s\",\"message\":\"refused\"}".formatted(code));
    }

    @Test
    void capturedPaymentPresent_returnsCapturedSnapshotWithServiceToken() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer svc-token"))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));

        Optional<PaymentStatusSnapshot> snapshot = client.findCapturedByOrderId(ORDER_ID);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().status()).isEqualTo("CAPTURED");
        assertThat(snapshot.get().id()).isEqualTo(PAYMENT_CAPTURED);
        assertThat(snapshot.get().orderId()).isEqualTo(ORDER_ID);
        assertThat(snapshot.get().amount()).isEqualByComparingTo(CAPTURED_AMOUNT);
        assertThat(snapshot.get().currency()).isEqualTo("VND");
    }

    @Test
    void theLookupUsesTheDedicatedCapturedEndpointNotThePagedList() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));

        client.findCapturedByOrderId(ORDER_ID);

        server.verify();
    }

    @Test
    void noCapturedPayment_returnsEmpty() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findCapturedByOrderId(ORDER_ID)).isEmpty();
    }

    @Test
    void emptyBody_returnsEmpty() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withSuccess("{\"success\":true,\"code\":\"OK\",\"data\":null}", MediaType.APPLICATION_JSON));

        assertThat(client.findCapturedByOrderId(ORDER_ID)).isEmpty();
    }

    @Test
    void serverError_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withServerError());

        assertThat(findFailingClosed()).isEmpty();
    }

    @Test
    void ioTimeout_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withException(new IOException("read timeout")));

        assertThat(findFailingClosed()).isEmpty();
    }

    @Test
    void malformedBody_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withSuccess("not-json{", MediaType.APPLICATION_JSON));

        assertThat(findFailingClosed()).isEmpty();
    }

    @Test
    void aPartiallyRefundedPaymentIsRefundable() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(partiallyRefundedJson(), MediaType.APPLICATION_JSON));

        Optional<PaymentStatusSnapshot> snapshot = client.findRefundableByOrderId(ORDER_ID);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(snapshot.get().refundedAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void theRefundPathFindsTheRefundablePaymentThroughTheRefundableEndpoint() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFUND_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.refundByOrderId(ORDER_ID)).isTrue();

        server.verify();
    }

    @Test
    void theRefundPathDoesNotRefundAnOrderWithoutARefundablePayment() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.refundByOrderId(ORDER_ID)).isFalse();

        server.verify();
    }

    @Test
    void theFullRefundDeclaresTheCapturedAmountCurrencyAndTheApprovalReference() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFUND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"amount\":2499.00,\"currency\":\"VND\",\"refundReference\":\"%s\"}"
                    .formatted(ORDER_ID)))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.refundByOrderId(ORDER_ID)).isTrue();

        server.verify();
    }

    @Test
    void theRefundPathSendsTheApprovedAmountCurrencyAndApprovalReference() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFUND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"amount\":100.00,\"currency\":\"VND\",\"refundReference\":\"%s\"}"
                    .formatted(REFUND_REFERENCE)))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.refundByOrderId(ORDER_ID, new BigDecimal("100.00"), "VND", REFUND_REFERENCE)).isTrue();

        server.verify();
    }

    @Test
    void theRefundPathRefusesAnAmountAboveTheRemainingRefundableAmount() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refundByOrderId(ORDER_ID, new BigDecimal("3000.00"), "VND",
                REFUND_REFERENCE))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PAY-5007"));

        server.verify();
    }

    @Test
    void theRefundPathRefusesANonPositiveAmount() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refundByOrderId(ORDER_ID, BigDecimal.ZERO, "VND", REFUND_REFERENCE))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PAY-5007"));

        server.verify();
    }

    @Test
    void theRefundPathRefusesACurrencyThatIsNotThePaymentCurrency() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refundByOrderId(ORDER_ID, new BigDecimal("100.00"), "USD",
                REFUND_REFERENCE))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PAY-5010"));

        server.verify();
    }

    @Test
    void theRefundPathPropagatesTheRefusalOfThePaymentService() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(partiallyRefundedJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFUND_URL))
            .andRespond(errorResponse(HttpStatus.CONFLICT, "PAY-5006"));

        assertThatThrownBy(() -> client.refundByOrderId(ORDER_ID, new BigDecimal("100.00"), "VND",
                REFUND_REFERENCE))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PAY-5006"));

        server.verify();
    }

    @Test
    void aFailedPaymentServiceIsReportedAsServiceUnavailable() {
        stubToken();
        server.expect(requestTo(REFUNDABLE_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFUND_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.refundByOrderId(ORDER_ID, new BigDecimal("100.00"), "VND",
                REFUND_REFERENCE))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ERR-0503"));

        server.verify();
    }

    private Optional<PaymentStatusSnapshot> findFailingClosed() {
        try {
            return client.findCapturedByOrderId(ORDER_ID);
        } catch (RuntimeException ex) {
            throw new AssertionError("Client must fail closed (empty), not throw", ex);
        }
    }
}
