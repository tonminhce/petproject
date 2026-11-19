package com.shop.paymentservice.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.paymentservice.config.MoMoProviderConfiguredCondition;
import com.shop.paymentservice.config.PaymentMoMoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Component
@Conditional(MoMoProviderConfiguredCondition.class)
@Slf4j
public class MoMoProvider implements PaymentProvider {

    static final String CREATE_PATH = "/v2/gateway/api/create";
    static final String REFUND_PATH = "/v2/gateway/api/refund";
    static final String REQUEST_TYPE = "captureWallet";
    static final int SUCCESS_RESULT_CODE = 0;

    private final PaymentMoMoProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public MoMoProvider(PaymentMoMoProperties properties,
            @Qualifier("momoRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "MOMO";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        if (!"VND".equalsIgnoreCase(currency)) {
            return ProviderResult.rejected("MOMO_UNSUPPORTED_CURRENCY");
        }
        if (amount.signum() <= 0) {
            return ProviderResult.rejected("MOMO_AMOUNT_NOT_POSITIVE");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            return ProviderResult.rejected("MOMO_AMOUNT_NOT_INTEGRAL");
        }
        if (!properties.isConfigured()) {
            return ProviderResult.indeterminate("MOMO_NOT_CONFIGURED");
        }
        String orderId = paymentId.toString();
        long amountValue = amount.longValueExact();
        String amountText = Long.toString(amountValue);

        Map<String, String> signed = new TreeMap<>();
        signed.put("accessKey", properties.accessKey());
        signed.put("amount", amountText);
        signed.put("extraData", "");
        signed.put("ipnUrl", properties.notifyUrl());
        signed.put("orderId", orderId);
        signed.put("orderInfo", properties.orderInfo());
        signed.put("partnerCode", properties.partnerCode());
        signed.put("redirectUrl", properties.returnUrl());
        signed.put("requestId", orderId);
        signed.put("requestType", REQUEST_TYPE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partnerCode", properties.partnerCode());
        payload.put("requestId", orderId);
        payload.put("orderId", orderId);
        payload.put("amount", amountValue);
        payload.put("orderInfo", properties.orderInfo());
        payload.put("redirectUrl", properties.returnUrl());
        payload.put("ipnUrl", properties.notifyUrl());
        payload.put("requestType", REQUEST_TYPE);
        payload.put("extraData", "");
        payload.put("lang", "vi");
        payload.put("signature", sign(signed));

        String body = post(CREATE_PATH, payload, paymentId);
        if (body == null) {
            return ProviderResult.indeterminate("MOMO_CREATE_UNAVAILABLE");
        }
        return captureOutcome(body, orderId, amount);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        return refund(paymentId, amount, idempotencyKey, null);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey, String providerIntentId) {
        if (!properties.isConfigured()) {
            return ProviderResult.indeterminate("MOMO_NOT_CONFIGURED");
        }
        String transactionId = providerIntentId == null ? "" : providerIntentId.trim();
        if (!transactionId.matches("\\d+")) {
            return ProviderResult.rejected("MOMO_TRANSACTION_ID_MISSING");
        }
        if (amount.signum() <= 0) {
            return ProviderResult.rejected("MOMO_AMOUNT_NOT_POSITIVE");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            return ProviderResult.rejected("MOMO_AMOUNT_NOT_INTEGRAL");
        }
        String refundOrderId = "RF" + paymentId.toString().replace("-", "");
        long amountValue = amount.longValueExact();

        Map<String, String> signed = new TreeMap<>();
        signed.put("accessKey", properties.accessKey());
        signed.put("amount", Long.toString(amountValue));
        signed.put("description", "");
        signed.put("orderId", refundOrderId);
        signed.put("partnerCode", properties.partnerCode());
        signed.put("requestId", refundOrderId);
        signed.put("transId", transactionId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partnerCode", properties.partnerCode());
        payload.put("orderId", refundOrderId);
        payload.put("requestId", refundOrderId);
        payload.put("amount", amountValue);
        payload.put("transId", Long.parseLong(transactionId));
        payload.put("description", "");
        payload.put("lang", "vi");
        payload.put("signature", sign(signed));

        String body = post(REFUND_PATH, payload, paymentId);
        if (body == null) {
            return ProviderResult.indeterminate("MOMO_REFUND_UNAVAILABLE");
        }
        JsonNode response = parse(body);
        if (response == null) {
            return ProviderResult.indeterminate("MOMO_MALFORMED_RESPONSE");
        }
        if (!refundResponseBoundToRequest(response, refundOrderId, amountValue)) {
            return ProviderResult.indeterminate("MOMO_REFUND_RESPONSE_MISMATCH");
        }
        if (!refundResponseSignatureValid(response, refundOrderId, amountValue, transactionId)) {
            return ProviderResult.indeterminate("MOMO_REFUND_SIGNATURE_INVALID");
        }
        int resultCode = response.path("resultCode").asInt(-1);
        if (resultCode != SUCCESS_RESULT_CODE) {
            return ProviderResult.rejected("MOMO_RESULT_CODE_" + resultCode);
        }
        return ProviderResult.confirmed(transactionId);
    }

    private boolean refundResponseBoundToRequest(JsonNode response, String refundOrderId, long amountValue) {
        String orderId = response.path("orderId").asText("");
        String amount = response.path("amount").asText("");
        if (!orderId.isEmpty() && !orderId.equals(refundOrderId)) {
            return false;
        }
        return amount.isEmpty() || amount.equals(Long.toString(amountValue));
    }

    private boolean refundResponseSignatureValid(JsonNode response, String refundOrderId, long amountValue,
            String transactionId) {
        String signature = response.path("signature").asText(null);
        if (signature == null || signature.isBlank()) {
            return true;
        }
        Map<String, String> signed = new TreeMap<>();
        signed.put("accessKey", properties.accessKey());
        signed.put("amount", Long.toString(amountValue));
        signed.put("description", "");
        signed.put("orderId", response.path("orderId").asText(refundOrderId));
        signed.put("partnerCode", properties.partnerCode());
        signed.put("requestId", response.path("requestId").asText(refundOrderId));
        signed.put("transId", response.path("transId").asText(transactionId));
        return MessageDigest.isEqual(sign(signed).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private ProviderResult captureOutcome(String body, String orderId, BigDecimal amount) {
        JsonNode response = parse(body);
        if (response == null) {
            return ProviderResult.indeterminate("MOMO_MALFORMED_RESPONSE");
        }
        if (!responseSignatureValid(response)) {
            return ProviderResult.indeterminate("MOMO_RESPONSE_SIGNATURE_INVALID");
        }
        int resultCode = response.path("resultCode").asInt(-1);
        if (resultCode != SUCCESS_RESULT_CODE) {
            return ProviderResult.rejected("MOMO_RESULT_CODE_" + resultCode);
        }
        JsonNode reportedAmount = response.path("amount");
        if (reportedAmount.isNumber() && reportedAmount.asLong() != amount.longValueExact()) {
            return ProviderResult.rejected("MOMO_AMOUNT_MISMATCH");
        }
        String payUrl = response.path("payUrl").asText(null);
        if (payUrl == null || payUrl.isBlank()) {
            return ProviderResult.indeterminate("MOMO_PAY_URL_MISSING");
        }
        return ProviderResult.requiresConfirmation(orderId, payUrl);
    }

    private String post(String path, Map<String, Object> payload, UUID paymentId) {
        try {
            return restClient.post()
                    .uri(URI.create(properties.baseUrl() + path))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException | JsonProcessingException ex) {
            log.error("MoMo call {} failed for payment {}", path, paymentId, ex);
            return null;
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            log.error("MoMo response could not be parsed");
            return null;
        }
    }

    private boolean responseSignatureValid(JsonNode response) {
        String signature = response.path("signature").asText(null);
        if (signature == null || signature.isBlank()) {
            return false;
        }
        Map<String, String> signed = new TreeMap<>();
        signed.put("accessKey", properties.accessKey());
        signed.put("amount", response.path("amount").asText(""));
        signed.put("message", response.path("message").asText(""));
        signed.put("orderId", response.path("orderId").asText(""));
        signed.put("partnerCode", response.path("partnerCode").asText(""));
        signed.put("payUrl", response.path("payUrl").asText(""));
        signed.put("requestId", response.path("requestId").asText(""));
        signed.put("responseTime", response.path("responseTime").asText(""));
        signed.put("resultCode", response.path("resultCode").asText(""));
        return MessageDigest.isEqual(sign(signed).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(Map<String, String> fields) {
        StringBuilder data = new StringBuilder();
        fields.forEach((key, value) -> {
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(key).append('=').append(value == null ? "" : value);
        });
        return hmacSHA256(properties.secretKey(), data.toString());
    }

    public static String hmacSHA256(String key, String data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKey);
            byte[] bytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", ex);
        }
    }
}
