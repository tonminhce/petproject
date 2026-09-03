package com.shop.shippingservice.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class WebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int SHA256_HEX_LENGTH = 64;

    private WebhookSignatureVerifier() {
    }

    public static boolean verify(String secret, byte[] rawBody, String signatureHeader) {
        if (secret == null || secret.isBlank() || rawBody == null) {
            return false;
        }
        if (signatureHeader == null || signatureHeader.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        try {
            byte[] provided = HexFormat.of().parseHex(signatureHeader);
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] expected = mac.doFinal(rawBody);
            return MessageDigest.isEqual(expected, provided);
        } catch (RuntimeException | GeneralSecurityException e) {
            return false;
        }
    }
}
