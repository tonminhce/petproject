package com.shop.paymentservice.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.paymentservice.config.PaymentVNPayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Component
@Slf4j
public class VNPayProvider implements PaymentProvider {

    static final String API_VERSION = "2.1.0";
    static final String COMMAND_PAY = "pay";
    static final String COMMAND_REFUND = "refund";
    static final String TRANSACTION_PATH = "/merchant_webapi/api/transaction";
    static final String SUCCESS_RESPONSE_CODE = "00";
    static final ZoneId PSP_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final DateTimeFormatter PSP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PaymentVNPayProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public VNPayProvider(PaymentVNPayProperties properties,
            @Qualifier("vnpayRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "VNPAY";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        if (!"VND".equalsIgnoreCase(currency)) {
            return ProviderResult.rejected("VNPAY_UNSUPPORTED_CURRENCY");
        }
        if (amount.signum() <= 0) {
            return ProviderResult.rejected("VNPAY_AMOUNT_NOT_POSITIVE");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            return ProviderResult.rejected("VNPAY_AMOUNT_NOT_INTEGRAL");
        }
        if (!properties.isConfigured()) {
            return ProviderResult.indeterminate("VNPAY_NOT_CONFIGURED");
        }
        String txnRef = paymentId.toString().replace("-", "");
        Instant now = Instant.now();

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", API_VERSION);
        params.put("vnp_Command", COMMAND_PAY);
        params.put("vnp_TmnCode", properties.tmnCode());
        params.put("vnp_Amount", pspAmount(amount));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_OrderInfo", properties.orderInfo());
        params.put("vnp_ReturnUrl", properties.returnUrl());
        params.put("vnp_NotifyUrl", properties.notifyUrl());
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_IpAddr", properties.ipAddress());
        params.put("vnp_Locale", "vn");
        params.put("vnp_CreateDate", pspDate(now));
        params.put("vnp_ExpireDate", pspDate(now.plusSeconds(900)));
        if (!properties.bankCode().isBlank()) {
            params.put("vnp_BankCode", properties.bankCode());
        }
        params.put("vnp_SecureHash", sign(params));

        return ProviderResult.requiresConfirmation(txnRef, properties.paymentUrl() + "?" + query(params));
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        return refund(paymentId, amount, idempotencyKey, null);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey, String providerIntentId) {
        if (!properties.isConfigured()) {
            return ProviderResult.indeterminate("VNPAY_NOT_CONFIGURED");
        }
        if (amount.signum() <= 0) {
            return ProviderResult.rejected("VNPAY_AMOUNT_NOT_POSITIVE");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            return ProviderResult.rejected("VNPAY_AMOUNT_NOT_INTEGRAL");
        }
        String txnRef = providerIntentId == null || providerIntentId.isBlank()
                ? paymentId.toString().replace("-", "")
                : providerIntentId.trim();

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", API_VERSION);
        params.put("vnp_Command", COMMAND_REFUND);
        params.put("vnp_TmnCode", properties.tmnCode());
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_TransactionDate", transactionDate());
        params.put("vnp_TransactionType", properties.refundType());
        params.put("vnp_Amount", pspAmount(amount));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_OrderInfo", properties.orderInfo());
        if (!properties.userName().isBlank()) {
            params.put("vnp_UserName", properties.userName());
        }
        params.put("vnp_SecureHash", sign(params));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);

        String body;
        try {
            body = restClient.post()
                    .uri(URI.create(properties.apiUrl() + TRANSACTION_PATH))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            log.error("VNPay refund call failed for payment {}", paymentId, ex);
            return ProviderResult.indeterminate("VNPAY_REFUND_UNAVAILABLE");
        }
        if (body == null || body.isBlank()) {
            return ProviderResult.indeterminate("VNPAY_MALFORMED_RESPONSE");
        }
        JsonNode response;
        try {
            response = objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            log.error("VNPay refund response could not be parsed for payment {}", paymentId);
            return ProviderResult.indeterminate("VNPAY_MALFORMED_RESPONSE");
        }
        String responseCode = response.path("vnp_ResponseCode").asText("");
        if (!SUCCESS_RESPONSE_CODE.equals(responseCode)) {
            return ProviderResult.rejected("VNPAY_RESPONSE_CODE_" + responseCode);
        }
        return ProviderResult.confirmed(txnRef);
    }

    private String transactionDate() {
        return properties.transactionDate().isBlank() ? pspDate(Instant.now()) : properties.transactionDate();
    }

    private String pspAmount(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).longValueExact() + "";
    }

    private String pspDate(Instant instant) {
        return PSP_DATE_FORMAT.format(instant.atZone(PSP_ZONE));
    }

    private String query(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return query.toString();
    }

    private String sign(Map<String, String> params) {
        StringBuilder data = new StringBuilder();
        params.forEach((key, value) -> {
            if (value == null || value.isBlank() || "vnp_SecureHash".equals(key) || "vnp_SecureHashType".equals(key)) {
                return;
            }
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(key).append('=').append(value);
        });
        return hmacSHA512(properties.hashSecret(), data.toString());
    }

    public static String hmacSHA512(String key, String data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac.init(secretKey);
            byte[] bytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA512", ex);
        }
    }
}
