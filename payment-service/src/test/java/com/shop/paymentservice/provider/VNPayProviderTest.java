package com.shop.paymentservice.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.paymentservice.config.PaymentVNPayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VNPayProviderTest {

    private static final String PAYMENT_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static final String API_URL = "https://sandbox.vnpayment.vn";
    private static final String TRANSACTION_URL = API_URL + "/merchant_webapi/api/transaction";
    private static final String TMN_CODE = "TMNCODE";
    private static final String HASH_SECRET = "HASHSECRET";
    private static final String RETURN_URL = "https://shop.test/vnpay/return";
    private static final String NOTIFY_URL = "https://shop.test/vnpay/ipn";
    private static final String ORDER_INFO = "Payment";

    private MockRestServiceServer server;
    private VNPayProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentVNPayProperties properties = new PaymentVNPayProperties(PAYMENT_URL, API_URL, TMN_CODE, HASH_SECRET,
                RETURN_URL, NOTIFY_URL, "", ORDER_INFO, "127.0.0.1", "", "partial", "", 30000L);
        provider = new VNPayProvider(properties, builder.build(), new ObjectMapper());
    }

    @Test
    void name_returnsVnpay() {
        assertThat(provider.name()).isEqualTo("VNPAY");
    }

    @Test
    void capture_buildsASignedRedirectAndWaitsForTheCustomerToPay() {
        UUID paymentId = UUID.randomUUID();

        PaymentProvider.ProviderResult result = provider.capture(paymentId, BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REQUIRES_CONFIRMATION);
        assertThat(result.providerEventId()).isEqualTo(paymentId.toString().replace("-", ""));

        Map<String, String> params = queryParams(result.clientSecret());
        assertThat(params).containsEntry("vnp_Command", "pay");
        assertThat(params).containsEntry("vnp_CurrCode", "VND");
        assertThat(params).containsEntry("vnp_TmnCode", TMN_CODE);
        assertThat(params).containsEntry("vnp_ReturnUrl", RETURN_URL);
        assertThat(params).containsEntry("vnp_NotifyUrl", NOTIFY_URL);
        assertThat(params).containsEntry("vnp_Amount", "5000000");
        assertThat(params).containsEntry("vnp_TxnRef", paymentId.toString().replace("-", ""));
        assertThat(params.get("vnp_CreateDate")).matches("\\d{14}");
        assertThat(params.get("vnp_SecureHash")).isEqualTo(expectedSignature(params));
    }

    @Test
    void capture_rejectsACurrencyTheProviderDoesNotSupport() {
        PaymentProvider.ProviderResult result = provider.capture(UUID.randomUUID(), BigDecimal.valueOf(10), "USD", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("VNPAY_UNSUPPORTED_CURRENCY");
    }

    @Test
    void capture_rejectsAFractionalAmount() {
        PaymentProvider.ProviderResult result = provider.capture(UUID.randomUUID(), BigDecimal.valueOf(500.5), "VND",
                "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("VNPAY_AMOUNT_NOT_INTEGRAL");
    }

    @Test
    void capture_isIndeterminateWhenTheProviderIsNotConfigured() {
        PaymentVNPayProperties unconfigured = new PaymentVNPayProperties("", "", "", "",
                "", "", "", ORDER_INFO, "127.0.0.1", "", "partial", "", 30000L);
        VNPayProvider unconfiguredProvider = new VNPayProvider(unconfigured, RestClient.builder().build(),
                new ObjectMapper());

        PaymentProvider.ProviderResult result = unconfiguredProvider.capture(UUID.randomUUID(),
                BigDecimal.valueOf(50000), "VND", "key-1");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("VNPAY_NOT_CONFIGURED");
    }

    @Test
    void refund_callsTheTransactionApiAndReportsTheProviderResponseCode() {
        UUID paymentId = UUID.randomUUID();
        String txnRef = paymentId.toString().replace("-", "");
        server.expect(requestTo(TRANSACTION_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("vnp_Command=refund")))
                .andExpect(content().string(containsString("vnp_TxnRef=" + txnRef)))
                .andRespond(withSuccess("{\"vnp_ResponseCode\":\"00\",\"vnp_ResponseMessage\":\"Success\"}",
                        MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.refund(paymentId, BigDecimal.valueOf(50000), "key-1", txnRef);

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.CONFIRMED);
        assertThat(result.providerEventId()).isEqualTo(txnRef);
        server.verify();
    }

    @Test
    void refund_rejectsWhenTheProviderRefusesTheRefund() {
        server.expect(requestTo(TRANSACTION_URL))
                .andRespond(withSuccess("{\"vnp_ResponseCode\":\"04\",\"vnp_ResponseMessage\":\"Invalid date\"}",
                        MediaType.APPLICATION_JSON));

        PaymentProvider.ProviderResult result = provider.refund(UUID.randomUUID(), BigDecimal.valueOf(50000), "key-1",
                "txnref");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("VNPAY_RESPONSE_CODE_04");
        server.verify();
    }

    @Test
    void refund_isIndeterminateWhenTheProviderIsUnreachable() {
        server.expect(requestTo(TRANSACTION_URL)).andRespond(withServerError());

        PaymentProvider.ProviderResult result = provider.refund(UUID.randomUUID(), BigDecimal.valueOf(50000), "key-1",
                "txnref");

        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("VNPAY_REFUND_UNAVAILABLE");
        server.verify();
    }

    @Test
    void hmacSHA512_matchesTheRfc4231Vector() {
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);

        assertThat(VNPayProvider.hmacSHA512(new String(key, StandardCharsets.ISO_8859_1), "Hi There"))
                .isEqualTo("87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274"
                        + "eaea3f4e4be9d914eeb61f1702e696c203a126854");
    }

    private String expectedSignature(Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("vnp_SecureHash");
        signed.remove("vnp_SecureHashType");
        StringBuilder data = new StringBuilder();
        signed.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(key).append('=').append(value);
        });
        try {
            Mac hmac = Mac.getInstance("HmacSHA512");
            hmac.init(new SecretKeySpec(HASH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(hmac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, String> queryParams(String url) {
        Map<String, String> params = new TreeMap<>();
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
