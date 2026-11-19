package com.shop.paymentservice.webhook;

import com.shop.paymentservice.config.PaymentVNPayProperties;
import com.shop.paymentservice.provider.VNPayEncoding;
import com.shop.paymentservice.provider.VNPayProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

@Component
public class VNPayCallbackVerifier {

    private final PaymentVNPayProperties properties;

    public VNPayCallbackVerifier(PaymentVNPayProperties properties) {
        this.properties = properties;
    }

    public boolean verify(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        String secureHash = params.get("vnp_SecureHash");
        if (secureHash == null || secureHash.isBlank()) {
            return false;
        }
        Map<String, String> encoded = new TreeMap<>();
        params.forEach((key, value) -> {
            if ("vnp_SecureHash".equals(key) || "vnp_SecureHashType".equals(key)) {
                return;
            }
            encoded.put(VNPayEncoding.encode(key), VNPayEncoding.encode(value));
        });
        StringBuilder data = new StringBuilder();
        encoded.forEach((key, value) -> {
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(key).append('=').append(value);
        });
        String expected = VNPayProvider.hmacSHA512(properties.hashSecret(), data.toString());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                secureHash.getBytes(StandardCharsets.UTF_8));
    }
}
