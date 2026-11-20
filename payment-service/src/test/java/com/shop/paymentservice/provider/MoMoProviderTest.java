package com.shop.paymentservice.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.paymentservice.config.PaymentMoMoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MoMoProviderTest {

    private static final String BASE_URL = "https://test-payment.momo.vn";
    private static final String CREATE_URL = BASE_URL + "/v2/gateway/api/create";
    private static final String REFUND_URL = BASE_URL + "/v2/gateway/api/refund";
    private static final String PARTNER = "PARTNER";
    private static final String ACCESS_KEY = "ACCESS";
    private static final String SECRET = "SECRET";
    private static final String RETURN_URL = "https://shop.test/momo/return";
    private static final String NOTIFY_URL = "https://shop.test/momo/ipn";
    private static final String ORDER_INFO = "Payment";
    private static final String PAY_URL = "https://test-payment.momo.vn/app/pay";

    private MockRestServiceServer server;
    private MoMoProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentMoMoProperties properties = new PaymentMoMoProperties(BASE_URL, PARTNER, ACCESS_KEY, SECRET,
                RETURN_URL, NOTIFY_URL, ORDER_INFO, 30000L);
        provider = new MoMoProvider(properties, builder.build(), new ObjectMapper());
    }

    @Test
    void name_returnsMomo() {
        assertThat(provider.name()).isEqualTo("MOMO");
    }

    @Test
    void capture_callsTheProviderAndReturnsThePaymentUrl() {
        UUID paymentId = UUID.randomUUID();
        long amount = 50000L;
        server.expect(requestTo(CREATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.partnerCode").value(PARTNER))
                .andExpect(jsonPath("$.orderId").value(paymentId.toString()))
                .andExpect(jsonPath("$.requestId").value(paymentId.toString()))
                .andExpect(jsonPath("$.amount").value(amount))
                .andExpect(jsonPath("$.requestType").value("captureWallet"))
                .andExpect(jsonPath("$.signature").value(expectedCreateSignature(paymentId, amount)))
                .andRespond(withSuccess(successResponse(paymentId, amount), MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.capture(paymentId, BigDecimal.valueOf(amount), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REQUIRES_CONFIRMATION);
        assertThat(result.clientSecret()).isEqualTo(PAY_URL);
        assertThat(result.providerEventId()).isEqualTo(paymentId.toString());
        server.verify();
    }

    @Test
    void capture_rejectsACurrencyTheProviderDoesNotSupportWithoutCallingIt() {
        PaymentProvider.ProviderResult result = provider.capture(UUID.randomUUID(), BigDecimal.valueOf(1000), "USD", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("MOMO_UNSUPPORTED_CURRENCY");
        server.verify();
    }

    @Test
    void capture_rejectsWhenTheProviderReportsAFailedResultCode() {
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo(CREATE_URL))
                .andRespond(withSuccess(errorResponse(paymentId.toString(), "Transaction rejected", 1002),
                        MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.capture(paymentId, BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("MOMO_RESULT_CODE_1002");
        server.verify();
    }

    @Test
    void capture_isIndeterminateWhenTheResponseSignatureDoesNotVerify() {
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo(CREATE_URL))
                .andRespond(withSuccess(successResponse(paymentId, 50000L)
                        .replace(successSignature(paymentId.toString(), 50000L),
                                "0000000000000000000000000000000000000000000000000000000000000000"),
                        MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.capture(paymentId, BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("MOMO_RESPONSE_SIGNATURE_INVALID");
        server.verify();
    }

    @Test
    void capture_isIndeterminateWhenTheProviderIsUnreachable() {
        server.expect(requestTo(CREATE_URL)).andRespond(withServerError());

        PaymentProvider.ProviderResult result = provider.capture(UUID.randomUUID(), BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("MOMO_CREATE_UNAVAILABLE");
        server.verify();
    }

    @Test
    void capture_rejectsWhenTheProviderReportsADifferentAmount() {
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo(CREATE_URL))
                .andRespond(withSuccess(successResponse(paymentId, 40000L), MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.capture(paymentId, BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("MOMO_AMOUNT_MISMATCH");
        server.verify();
    }

    @Test
    void capture_rejectsAFractionalAmountWithoutCallingTheProvider() {
        PaymentProvider.ProviderResult result = provider.capture(UUID.randomUUID(), BigDecimal.valueOf(500.5), "VND",
                "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("MOMO_AMOUNT_NOT_INTEGRAL");
        server.verify();
    }

    @Test
    void capture_signsTheSameAmountTextItTransmits() {
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo(CREATE_URL))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.signature").value(expectedCreateSignature(paymentId, 50000)))
                .andRespond(withSuccess(successResponse(paymentId, 50000L), MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.capture(paymentId, new BigDecimal("50000.00"), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REQUIRES_CONFIRMATION);
        server.verify();
    }

    @Test
    void refund_isRejectedWhenTheProviderTransactionIdIsUnknown() {
        PaymentProvider.ProviderResult result = provider.refund(UUID.randomUUID(), BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("MOMO_TRANSACTION_ID_MISSING");
        server.verify();
    }

    @Test
    void refund_rejectsACurrencyTheProviderDoesNotSupportWithoutCallingIt() {
        PaymentProvider.ProviderResult result = provider.refund(UUID.randomUUID(), BigDecimal.valueOf(50000), "USD",
                "key-1", "144492817");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("MOMO_UNSUPPORTED_CURRENCY");
        server.verify();
    }

    @Test
    void refund_callsTheProviderRefundEndpoint() {
        UUID paymentId = UUID.randomUUID();
        String refundOrderId = "RF" + paymentId.toString().replace("-", "");
        server.expect(requestTo(REFUND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId").value(refundOrderId))
                .andExpect(jsonPath("$.transId").value(144492817L))
                .andRespond(withSuccess("""
                        {"resultCode":0,"message":"Successful","orderId":"%s","amount":50000}
                        """.formatted(refundOrderId), MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.refund(paymentId, BigDecimal.valueOf(50000), "VND", "key-1",
                "144492817");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.CONFIRMED);
        assertThat(result.providerEventId()).isEqualTo("144492817");
        server.verify();
    }

    @Test
    void refund_isIndeterminateWhenTheResponseCarriesNoProofThatItBelongsToTheRequest() {
        server.expect(requestTo(REFUND_URL))
                .andRespond(withSuccess("{\"resultCode\":0,\"message\":\"Successful\"}", MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.refund(UUID.randomUUID(), BigDecimal.valueOf(50000), "VND", "key-1",
                "144492817");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("MOMO_REFUND_RESPONSE_MISMATCH");
        server.verify();
    }

    @Test
    void refund_isIndeterminateWhenTheResponseDoesNotBelongToTheRequest() {
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo(REFUND_URL))
                .andRespond(withSuccess("""
                        {"resultCode":0,"message":"Successful","orderId":"RF-somebody-else","amount":50000}
                        """, MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.refund(paymentId, BigDecimal.valueOf(50000), "VND", "key-1",
                "144492817");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("MOMO_REFUND_RESPONSE_MISMATCH");
        server.verify();
    }

    @Test
    void refund_isIndeterminateWhenTheResponseSignatureDoesNotVerify() {
        UUID paymentId = UUID.randomUUID();
        String refundOrderId = "RF" + paymentId.toString().replace("-", "");
        server.expect(requestTo(REFUND_URL))
                .andRespond(withSuccess("""
                        {"resultCode":0,"message":"Successful","orderId":"%s","amount":50000,\
                        "requestId":"%s","transId":144492817,"signature":"%s"}
                        """.formatted(refundOrderId, refundOrderId,
                        "0000000000000000000000000000000000000000000000000000000000000000"), MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.refund(paymentId, BigDecimal.valueOf(50000), "VND", "key-1",
                "144492817");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("MOMO_REFUND_SIGNATURE_INVALID");
        server.verify();
    }

    @Test
    void hmacSHA256_matchesTheKnownVector() {
        assertThat(MoMoProvider.hmacSHA256("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    private String expectedCreateSignature(UUID paymentId, long amount) {
        String orderId = paymentId.toString();
        String data = "accessKey=" + ACCESS_KEY
                + "&amount=" + amount
                + "&extraData="
                + "&ipnUrl=" + NOTIFY_URL
                + "&orderId=" + orderId
                + "&orderInfo=" + ORDER_INFO
                + "&partnerCode=" + PARTNER
                + "&redirectUrl=" + RETURN_URL
                + "&requestId=" + orderId
                + "&requestType=captureWallet";
        return hmacSha256(data);
    }

    private String successResponse(UUID paymentId, long amount) {
        String orderId = paymentId.toString();
        return """
                {"partnerCode":"%s","requestId":"%s","orderId":"%s","amount":%d,"responseTime":1700000000000,\
                "message":"Successful.","resultCode":0,"payUrl":"%s","signature":"%s"}
                """.formatted(PARTNER, orderId, orderId, amount, PAY_URL, successSignature(orderId, amount));
    }

    private String successSignature(String orderId, long amount) {        String data = "accessKey=" + ACCESS_KEY
                + "&amount=" + amount
                + "&message=Successful."
                + "&orderId=" + orderId
                + "&partnerCode=" + PARTNER
                + "&payUrl=" + PAY_URL
                + "&requestId=" + orderId
                + "&responseTime=1700000000000"
                + "&resultCode=0";
        return hmacSha256(data);
    }

    private String errorResponse(String orderId, String message, int resultCode) {
        String data = "accessKey=" + ACCESS_KEY
                + "&amount=50000"
                + "&message=" + message
                + "&orderId=" + orderId
                + "&partnerCode=" + PARTNER
                + "&payUrl="
                + "&requestId=" + orderId
                + "&responseTime=1700000000000"
                + "&resultCode=" + resultCode;
        return """
                {"partnerCode":"%s","requestId":"%s","orderId":"%s","amount":50000,"responseTime":1700000000000,\
                "message":"%s","resultCode":%d,"signature":"%s"}
                """.formatted(PARTNER, orderId, orderId, message, resultCode, hmacSha256(data));
    }

    private String hmacSha256(String data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
