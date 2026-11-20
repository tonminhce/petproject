package com.shop.paymentservice.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.service.WebhookEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.WEBHOOK_PAYMENTS)
@Slf4j
public class PspCallbackController {

    static final String MOMO_PROVIDER = "momo";
    static final String VNPAY_PROVIDER = "vnpay";
    static final String CAPTURED = "CAPTURED";
    static final String FAILED = "FAILED";
    static final String VND = "VND";

    private final WebhookEventService webhookEventService;
    private final MoMoCallbackVerifier momoVerifier;
    private final VNPayCallbackVerifier vnpayVerifier;
    private final ObjectMapper objectMapper;

    public PspCallbackController(
            WebhookEventService webhookEventService,
            MoMoCallbackVerifier momoVerifier,
            VNPayCallbackVerifier vnpayVerifier,
            ObjectMapper objectMapper) {
        this.webhookEventService = webhookEventService;
        this.momoVerifier = momoVerifier;
        this.vnpayVerifier = vnpayVerifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/momo/ipn")
    public ResponseEntity<Void> momoIpn(@RequestBody byte[] rawBody) {
        JsonNode body = parse(rawBody);
        if (body == null || !momoVerifier.verify(body)) {
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        String status = momoStatus(body.path("resultCode").asInt(-1));
        if (status != null) {
            webhookEventService.handle(MOMO_PROVIDER, carrier(momoPayload(body, status)));
        }
        return ResponseEntity.noContent().build();
    }

    @RequestMapping(value = "/vnpay/ipn", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, String>> vnpayIpn(@RequestParam Map<String, String> params) {
        if (!vnpayVerifier.verify(params)) {
            return ResponseEntity.ok(Map.of("RspCode", "97", "RspMsg", "Invalid Checksum"));
        }
        UUID paymentId = paymentIdFromTxnRef(params.get("vnp_TxnRef"));
        if (paymentId == null) {
            return ResponseEntity.ok(Map.of("RspCode", "97", "RspMsg", "Invalid Transaction Reference"));
        }
        String status = vnpayStatus(params.getOrDefault("vnp_TransactionStatus", ""));
        if (status != null) {
            webhookEventService.handle(VNPAY_PROVIDER, carrier(vnpayPayload(params, paymentId, status)));
        }
        return ResponseEntity.ok(Map.of("RspCode", "00", "RspMsg", "Confirm Success"));
    }

    static String momoStatus(int resultCode) {
        if (resultCode == 0) {
            return CAPTURED;
        }
        if (resultCode == 1002 || resultCode == 1005) {
            return FAILED;
        }
        return null;
    }

    static String vnpayStatus(String transactionStatus) {
        if ("00".equals(transactionStatus)) {
            return CAPTURED;
        }
        if ("01".equals(transactionStatus) || "04".equals(transactionStatus)) {
            return FAILED;
        }
        return null;
    }

    private WebhookPayload momoPayload(JsonNode body, String status) {
        String orderId = body.path("orderId").asText("");
        String requestId = body.path("requestId").asText("");
        String transId = body.path("transId").asText("");
        String eventId = transId.isBlank() || "0".equals(transId) ? requestId : transId;
        return WebhookPayload.builder()
                .eventId(eventId)
                .eventType("momo.ipn")
                .paymentId(orderId)
                .amount(amountOf(body.path("amount").asText("")))
                .currency(carrierCurrency(orderId))
                .status(status)
                .providerEventId(eventId)
                .providerIntentId(transId.isBlank() || "0".equals(transId) ? null : transId)
                .build();
    }

    private WebhookPayload vnpayPayload(Map<String, String> params, UUID paymentId, String status) {
        String transactionNo = params.getOrDefault("vnp_TransactionNo", "");
        String txnRef = params.getOrDefault("vnp_TxnRef", "");
        String eventId = transactionNo.isBlank() ? txnRef : transactionNo;
        return WebhookPayload.builder()
                .eventId(eventId)
                .eventType("vnpay.ipn")
                .paymentId(paymentId.toString())
                .amount(vnpayAmount(params.getOrDefault("vnp_Amount", "")))
                .currency(carrierCurrency(paymentId.toString()))
                .status(status)
                .providerEventId(eventId)
                .providerIntentId(txnRef)
                .build();
    }

    private String carrierCurrency(String paymentId) {
        String currency = webhookEventService.paymentCurrency(paymentId);
        if (currency == null || currency.isBlank()) {
            return null;
        }
        if (!VND.equalsIgnoreCase(currency)) {
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_MISMATCH, currency, VND);
        }
        return currency;
    }

    private BigDecimal amountOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            log.error("PSP callback carried a non numeric amount");
            return null;
        }
    }

    private BigDecimal vnpayAmount(String value) {
        BigDecimal minorUnits = amountOf(value);
        if (minorUnits == null) {
            return null;
        }
        try {
            return minorUnits.movePointLeft(2).setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            log.error("PSP callback carried an amount that is not a whole unit of the currency");
            return null;
        }
    }

    private UUID paymentIdFromTxnRef(String txnRef) {
        if (txnRef == null || txnRef.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(txnRef.substring(0, 8) + "-" + txnRef.substring(8, 12) + "-"
                    + txnRef.substring(12, 16) + "-" + txnRef.substring(16, 20) + "-" + txnRef.substring(20));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private byte[] carrier(WebhookPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("PSP callback could not be mapped to the carrier payload", ex);
        }
    }

    private JsonNode parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            log.error("PSP callback body could not be parsed");
            return null;
        }
    }
}
