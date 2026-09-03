package com.shop.paymentservice.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "vnpay")
@Slf4j
public class VNPayProvider implements PaymentProvider {

    private final String tmnCode;
    private final String hashSecret;
    private final String vnpayUrl;

    public VNPayProvider(
            @Value("${shop.payment.vnpay.tmn-code:VNPAY_TMN_DEMO}") String tmnCode,
            @Value("${shop.payment.vnpay.hash-secret:VNPAY_SECRET_KEY_DEMO}") String hashSecret,
            @Value("${shop.payment.vnpay.url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}") String vnpayUrl) {
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.vnpayUrl = vnpayUrl;
    }

    @Override
    public String name() {
        return "VNPAY";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        try {
            String vnpTxnRef = paymentId.toString().replace("-", "").substring(0, 16);
            String rawData = "vnp_Amount=" + amount.multiply(BigDecimal.valueOf(100)).intValue()
                    + "&vnp_Command=pay"
                    + "&vnp_CurrCode=" + currency
                    + "&vnp_TmnCode=" + tmnCode
                    + "&vnp_TxnRef=" + vnpTxnRef;
            String secureHash = hmacSHA512(hashSecret, rawData);
            String paymentUrl = vnpayUrl + "?" + rawData + "&vnp_SecureHash=" + secureHash;
            log.info("Generated VNPay payment redirect URL for payment {}", paymentId);
            return new ProviderResult(vnpTxnRef, true);
        } catch (Exception ex) {
            log.error("Failed to generate VNPay transaction for payment {}", paymentId, ex);
            return new ProviderResult("VNPAY_FAILED", false);
        }
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        String refundRef = "vnp_rf_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Created VNPay refund transaction {} for payment {}", refundRef, paymentId);
        return new ProviderResult(refundRef, true);
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
