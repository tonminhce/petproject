package com.shop.shippingservice.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret";
    private static final byte[] RAW_BODY = new byte[]{0x00, 0x01, 0x02, 'a', '{', '"', 'x', '"', '}', (byte) 0xFF, '\n'};

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validSignatureIsVerified() {
        String signature = hmacHex(SECRET, RAW_BODY);

        assertTrue(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, signature));
    }

    @Test
    void tamperedBodyIsRejected() {
        byte[] tampered = new byte[RAW_BODY.length];
        System.arraycopy(RAW_BODY, 0, tampered, 0, RAW_BODY.length);
        tampered[3] = 'b';
        String signature = hmacHex(SECRET, RAW_BODY);

        assertFalse(WebhookSignatureVerifier.verify(SECRET, tampered, signature));
    }

    @Test
    void wrongSecretIsRejected() {
        String signature = hmacHex("whsec_other_secret", RAW_BODY);

        assertFalse(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, signature));
    }

    @Test
    void nullHeaderIsRejected() {
        assertFalse(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, null));
    }

    @Test
    void blankHeaderIsRejected() {
        assertFalse(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, "   "));
    }

    @Test
    void shortHeaderIsRejected() {
        assertFalse(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, "deadbeef"));
    }

    @Test
    void prefixedHexHeaderIsRejected() {
        String signature = "sha256=" + hmacHex(SECRET, RAW_BODY);

        assertFalse(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, signature));
    }

    @Test
    void nonHexHeaderIsRejected() {
        assertFalse(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, "z".repeat(64)));
    }

    @Test
    void nullSecretIsRejected() {
        String signature = hmacHex(SECRET, RAW_BODY);

        assertFalse(WebhookSignatureVerifier.verify(null, RAW_BODY, signature));
    }

    @Test
    void nullBodyIsRejected() {
        String signature = hmacHex(SECRET, RAW_BODY);

        assertFalse(WebhookSignatureVerifier.verify(SECRET, null, signature));
    }

    @Test
    void uppercaseHexSignatureIsVerified() {
        String signature = hmacHex(SECRET, RAW_BODY).toUpperCase();

        assertTrue(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, signature));
    }
}
