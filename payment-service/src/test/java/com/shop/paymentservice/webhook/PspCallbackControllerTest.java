package com.shop.paymentservice.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.config.PaymentMoMoProperties;
import com.shop.paymentservice.config.PaymentVNPayProperties;
import com.shop.paymentservice.service.WebhookEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PspCallbackControllerTest {

    private static final String SECRET = "SECRET";
    private static final String ACCESS_KEY = "ACCESS";
    private static final String PARTNER = "PARTNER";
    private static final String HASH_SECRET = "HASHSECRET";
    private static final String TMN_CODE = "TMNCODE";
    private static final String PAYMENT_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebhookEventService webhookEventService;
    private PspCallbackController controller;

    @BeforeEach
    void setUp() {
        webhookEventService = mock(WebhookEventService.class);
        PaymentMoMoProperties momoProperties = new PaymentMoMoProperties("https://test-payment.momo.vn", PARTNER,
                ACCESS_KEY, SECRET, "https://shop.test/momo/return", "https://shop.test/momo/ipn", "Payment", 30000L);
        PaymentVNPayProperties vnpayProperties = new PaymentVNPayProperties(
                "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", "https://sandbox.vnpayment.vn", TMN_CODE,
                HASH_SECRET, "https://shop.test/vnpay/return", "https://shop.test/vnpay/ipn", "", "Payment",
                "127.0.0.1", "", "partial", "", 30000L);
        controller = new PspCallbackController(webhookEventService, new MoMoCallbackVerifier(momoProperties),
                new VNPayCallbackVerifier(vnpayProperties), objectMapper);
        when(webhookEventService.paymentCurrency(anyString())).thenReturn("VND");
    }

    @Test
    void momoCallbackWithAVerifiedSignatureIsAccepted() {
        Map<String, Object> body = momoBody("0", "Successful.", 144492817L);

        ResponseEntity<Void> response = controller.momoIpn(json(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String carrier = capturedCarrier("momo");
        assertThat(carrier).contains(PAYMENT_ID);
        assertThat(carrier).contains("\"status\":\"CAPTURED\"");
        assertThat(carrier).contains("\"amount\":50000");
        assertThat(carrier).contains("144492817");
    }

    @Test
    void momoCallback_carriesTheCurrencyOfThePaymentInTheCarrierPayload() {
        Map<String, Object> body = momoBody("0", "Successful.", 144492817L);

        controller.momoIpn(json(body));

        assertThat(capturedCarrier("momo")).contains("\"currency\":\"VND\"");
    }

    @Test
    void momoCallbackForAPaymentInAnotherCurrency_isRefused() {
        when(webhookEventService.paymentCurrency(PAYMENT_ID)).thenReturn("USD");
        Map<String, Object> body = momoBody("0", "Successful.", 144492817L);

        assertThatThrownBy(() -> controller.momoIpn(json(body)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_CURRENCY_MISMATCH.getCode()));
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void momoCallbackWithATamperedAmountIsRejected() {
        Map<String, Object> body = momoBody("0", "Successful.", 144492817L);
        String signature = (String) body.get("signature");
        body.put("amount", 99999);
        body.put("signature", signature);

        assertThatThrownBy(() -> controller.momoIpn(json(body)))
                .isInstanceOf(BusinessException.class);
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void momoCallbackWithoutASignatureIsRejected() {
        Map<String, Object> body = momoBody("0", "Successful.", 144492817L);
        body.remove("signature");

        assertThatThrownBy(() -> controller.momoIpn(json(body)))
                .isInstanceOf(BusinessException.class);
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void momoCallbackWithAFailedResultCodeIsMappedToFailed() {
        Map<String, Object> body = momoBody("1005", "Transaction expired", 0L);

        controller.momoIpn(json(body));

        assertThat(capturedCarrier("momo")).contains("\"status\":\"FAILED\"");
    }

    @Test
    void vnpayCallbackWithAVerifiedHashIsAccepted() {
        Map<String, String> params = vnpayParams();

        ResponseEntity<Map<String, String>> response = controller.vnpayIpn(params);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("RspCode", "00");
        String carrier = capturedCarrier("vnpay");
        assertThat(carrier).contains(PAYMENT_ID);
        assertThat(carrier).contains("\"status\":\"CAPTURED\"");
        assertThat(carrier).contains("\"amount\":50000");
    }

    @Test
    void vnpayCallback_carriesTheCurrencyOfThePaymentInTheCarrierPayload() {
        Map<String, String> params = vnpayParams();

        controller.vnpayIpn(params);

        assertThat(capturedCarrier("vnpay")).contains("\"currency\":\"VND\"");
    }

    @Test
    void vnpayCallbackForAPaymentInAnotherCurrency_isRefused() {
        when(webhookEventService.paymentCurrency(PAYMENT_ID)).thenReturn("USD");
        Map<String, String> params = vnpayParams();

        assertThatThrownBy(() -> controller.vnpayIpn(params))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_CURRENCY_MISMATCH.getCode()));
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void vnpayCallbackWithAnInvalidHashIsRejected() {
        Map<String, String> params = vnpayParams();
        params.put("vnp_Amount", "1");

        ResponseEntity<Map<String, String>> response = controller.vnpayIpn(params);

        assertThat(response.getBody()).containsEntry("RspCode", "97");
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void vnpayCallbackWithAnUnknownTransactionReferenceIsRejected() {
        Map<String, String> params = vnpayParams();
        params.put("vnp_TxnRef", "not-a-transaction-reference");

        ResponseEntity<Map<String, String>> response = controller.vnpayIpn(params);

        assertThat(response.getBody()).containsEntry("RspCode", "97");
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void momoCallbackWithAnUnknownResultCodeIsAcknowledgedWithoutAStateChange() {
        Map<String, Object> body = momoBody("9000", "Unknown error", 0L);

        ResponseEntity<Void> response = controller.momoIpn(json(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(webhookEventService, never()).handle(any(), any());
    }

    @Test
    void vnpayCallbackWithAPendingStatusIsAcknowledgedWithoutAStateChange() {
        Map<String, String> params = vnpayParams();
        params.put("vnp_TransactionStatus", "03");
        params.put("vnp_SecureHash", vnpayHash(params));

        ResponseEntity<Map<String, String>> response = controller.vnpayIpn(params);

        assertThat(response.getBody()).containsEntry("RspCode", "00");
        verify(webhookEventService, never()).handle(any(), any());
    }

    private String capturedCarrier(String provider) {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(webhookEventService).handle(eq(provider), captor.capture());
        return new String(captor.getValue(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> momoBody(String resultCode, String message, long transId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", PARTNER);
        body.put("requestId", "req-1");
        body.put("orderId", PAYMENT_ID);
        body.put("amount", 50000);
        body.put("message", message);
        body.put("responseTime", 1700000000000L);
        body.put("resultCode", Integer.parseInt(resultCode));
        body.put("payType", "web");
        body.put("extraData", "");
        body.put("transId", transId);
        body.put("signature", momoSignature(body));
        return body;
    }

    private String momoSignature(Map<String, Object> body) {
        StringBuilder data = new StringBuilder();
        MoMoCallbackVerifier.SIGNED_FIELDS.forEach(field -> {
            if (!"accessKey".equals(field) && !body.containsKey(field)) {
                return;
            }
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(field).append('=')
                    .append("accessKey".equals(field) ? ACCESS_KEY : String.valueOf(body.get(field)));
        });
        return com.shop.paymentservice.provider.MoMoProvider.hmacSHA256(SECRET, data.toString());
    }

    private byte[] json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, String> vnpayParams() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", TMN_CODE);
        params.put("vnp_TxnRef", PAYMENT_ID.replace("-", ""));
        params.put("vnp_Amount", "5000000");
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TransactionNo", "20170829153052");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_SecureHash", vnpayHash(params));
        return params;
    }

    private String vnpayHash(Map<String, String> params) {
        Map<String, String> encoded = new TreeMap<>();
        params.forEach((key, value) -> {
            if ("vnp_SecureHash".equals(key) || "vnp_SecureHashType".equals(key)) {
                return;
            }
            encoded.put(URLEncoder.encode(key, StandardCharsets.UTF_8),
                    URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        StringBuilder data = new StringBuilder();
        encoded.forEach((key, value) -> {
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(key).append('=').append(value);
        });
        return com.shop.paymentservice.provider.VNPayProvider.hmacSHA512(HASH_SECRET, data.toString());
    }
}
