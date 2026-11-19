package com.shop.paymentservice.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.paymentservice.config.PaymentMoMoProperties;
import com.shop.paymentservice.provider.MoMoProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class MoMoCallbackVerifier {

    static final List<String> SIGNED_FIELDS = List.of(
            "accessKey",
            "amount",
            "extraData",
            "message",
            "orderId",
            "orderInfo",
            "orderType",
            "partnerCode",
            "payType",
            "requestId",
            "responseTime",
            "resultCode",
            "transId");

    private final PaymentMoMoProperties properties;

    public MoMoCallbackVerifier(PaymentMoMoProperties properties) {
        this.properties = properties;
    }

    public boolean verify(JsonNode body) {
        if (body == null) {
            return false;
        }
        String signature = body.path("signature").asText(null);
        if (signature == null || signature.isBlank()) {
            return false;
        }
        StringBuilder data = new StringBuilder();
        for (String field : SIGNED_FIELDS) {
            String value;
            if ("accessKey".equals(field)) {
                value = properties.accessKey();
            } else {
                if (!body.has(field)) {
                    continue;
                }
                value = body.path(field).asText("");
            }
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(field).append('=').append(value);
        }
        String expected = MoMoProvider.hmacSHA256(properties.secretKey(), data.toString());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
