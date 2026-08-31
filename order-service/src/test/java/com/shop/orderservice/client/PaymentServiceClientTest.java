package com.shop.orderservice.client;

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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
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
    private static final UUID PAYMENT_PENDING = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PAYMENT_CAPTURED = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String PAYMENTS_URL = "http://payment.test/api/v1/payments?orderId=" + ORDER_ID;

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

    private static String paymentJson(UUID id, String status) {
        return """
            {"id":"%s","orderId":"%s","amount":110.00,"currency":"VND","status":"%s","provider":"VNPAY"}
            """.formatted(id, ORDER_ID, status);
    }

    private static String pageJson(String... entries) {
        return """
            {"success":true,"code":"OK","data":{"content":[%s],"page":0,"size":20,"totalElements":%d,"totalPages":1,"first":true,"last":true}}
            """.formatted(String.join(",", entries), entries.length);
    }

    @Test
    void capturedPaymentPresent_returnsCapturedSnapshotWithServiceToken() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer svc-token"))
            .andRespond(withSuccess(pageJson(
                paymentJson(PAYMENT_PENDING, "PENDING"),
                paymentJson(PAYMENT_CAPTURED, "CAPTURED")), MediaType.APPLICATION_JSON));

        Optional<PaymentStatusSnapshot> snapshot = client.findCapturedByOrderId(ORDER_ID);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().status()).isEqualTo("CAPTURED");
        assertThat(snapshot.get().id()).isEqualTo(PAYMENT_CAPTURED);
        assertThat(snapshot.get().orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void onlyPendingOrRefunded_returnsEmpty() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andRespond(withSuccess(pageJson(
                paymentJson(PAYMENT_PENDING, "PENDING"),
                paymentJson(PAYMENT_CAPTURED, "REFUNDED")), MediaType.APPLICATION_JSON));

        assertThat(client.findCapturedByOrderId(ORDER_ID)).isEmpty();
    }

    @Test
    void emptyPage_returnsEmpty() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andRespond(withSuccess(pageJson(), MediaType.APPLICATION_JSON));

        assertThat(client.findCapturedByOrderId(ORDER_ID)).isEmpty();
    }

    @Test
    void non2xxResponse_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(findFailingClosed()).isEmpty();
    }

    @Test
    void serverError_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andRespond(withServerError());

        assertThat(findFailingClosed()).isEmpty();
    }

    @Test
    void ioTimeout_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andRespond(withException(new IOException("read timeout")));

        assertThat(findFailingClosed()).isEmpty();
    }

    @Test
    void malformedBody_failsClosedReturnsEmpty() {
        stubToken();
        server.expect(requestTo(PAYMENTS_URL))
            .andRespond(withSuccess("not-json{", MediaType.APPLICATION_JSON));

        assertThat(findFailingClosed()).isEmpty();
    }

    /** Fail-closed contract: ANY client failure must surface as empty, never a raw throw. */
    private Optional<PaymentStatusSnapshot> findFailingClosed() {
        try {
            return client.findCapturedByOrderId(ORDER_ID);
        } catch (RuntimeException ex) {
            throw new AssertionError("Client must fail closed (empty), not throw", ex);
        }
    }
}
