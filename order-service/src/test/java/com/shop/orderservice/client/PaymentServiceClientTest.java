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
    private static final UUID PAYMENT_CAPTURED = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String CAPTURED_URL =
            "http://payment.test/api/v1/payments/by-order/" + ORDER_ID + "/captured";

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
        return """
            {"success":true,"code":"OK","data":{"id":"%s","orderId":"%s","status":"CAPTURED"}}
            """.formatted(PAYMENT_CAPTURED, ORDER_ID);
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
    }

    /**
     * The captured payment is looked up through the dedicated endpoint, NOT
     * through the paged list: a captured payment beyond the first page was
     * invisible to the paged lookup, the confirm gate failed closed and the
     * paid order was auto-cancelled.
     */
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

    /**
     * The refund path asks the same question before it refunds, so the new
     * endpoint is its only way to find the captured payment too.
     */
    @Test
    void theRefundPathFindsTheCapturedPaymentThroughTheSameEndpoint() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withSuccess(capturedJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://payment.test/api/v1/payments/" + PAYMENT_CAPTURED + "/refund"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.refundByOrderId(ORDER_ID)).isTrue();

        server.verify();
    }

    @Test
    void theRefundPathDoesNotRefundAnOrderWithoutACapturedPayment() {
        stubToken();
        server.expect(requestTo(CAPTURED_URL))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.refundByOrderId(ORDER_ID)).isFalse();

        server.verify();
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
