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
@Slf4j
public class MoMoProvider implements PaymentProvider {

    private final String partnerCode;
    private final String accessKey;
    private final String secretKey;

    public MoMoProvider(
            @Value("${shop.payment.momo.partner-code:MOMO_PARTNER_DEMO}") String partnerCode,
            @Value("${shop.payment.momo.access-key:MOMO_ACCESS_KEY_DEMO}") String accessKey,
            @Value("${shop.payment.momo.secret-key:MOMO_SECRET_KEY_DEMO}") String secretKey) {
        this.partnerCode = partnerCode;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public String name() {
        return "MOMO";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        try {
            String orderId = paymentId.toString();
            String rawSignature = "accessKey=" + accessKey
                    + "&amount=" + amount.longValue()
                    + "&orderId=" + orderId
                    + "&partnerCode=" + partnerCode;
            String signature = hmacSHA256(secretKey, rawSignature);
            log.info("Created MoMo transaction with signature for payment {}", paymentId);
            return new ProviderResult("momo_" + orderId, true);
        } catch (Exception ex) {
            log.error("Failed to generate MoMo transaction for payment {}", paymentId, ex);
            return new ProviderResult("MOMO_FAILED", false);
        }
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        String refundRef = "momo_rf_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Created MoMo refund transaction {} for payment {}", refundRef, paymentId);
        return new ProviderResult(refundRef, true);
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
