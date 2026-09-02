package com.shop.paymentservice.webhook;

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

    // ========================================================================
    // H8 — Stripe signature scheme t=<timestamp>,v1=<hex> (sha256 prefix optional)
    // ========================================================================

    private static String hmacHexOf(String secret, String signedPayload) {
        return hmacHex(secret, signedPayload.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripeHeader(long timestamp, String hexSignature) {
        return "t=" + timestamp + ",v1=" + hexSignature;
    }

    /** Case 1 (H8): Stripe-format header with correct HMAC over `t.body` is verified. */
    @Test
    void validStripeSignatureIsVerified() {
        long ts = java.time.Instant.now().getEpochSecond();
        byte[] utf8Body = "{\"x\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        String signedPayload = ts + "." + new String(utf8Body, StandardCharsets.UTF_8);
        String hex = hmacHex(SECRET, signedPayload.getBytes(StandardCharsets.UTF_8));
        String header = stripeHeader(ts, hex);

        assertTrue(WebhookSignatureVerifier.verify(SECRET, utf8Body, header, ts));
    }

    /** Case 2 (H8): bare 64-char hex (no scheme prefix) is still accepted — sha256 prefix optional. */
    @Test
    void sha256OnlyBareHexIsVerified() {
        String signature = hmacHex(SECRET, RAW_BODY);

        assertTrue(WebhookSignatureVerifier.verify(SECRET, RAW_BODY, signature));
    }

    /** Case 3 (H8): Stripe header missing the v1= component is rejected. */
    @Test
    void v1MissingStripeHeaderIsRejected() {
        long ts = java.time.Instant.now().getEpochSecond();
        byte[] utf8Body = "{\"x\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        String header = "t=" + ts;  // no v1=

        assertFalse(WebhookSignatureVerifier.verify(SECRET, utf8Body, header, ts));
    }

    // ========================================================================
    // H27 — Webhook timestamp/replay protection (Stripe-style, 5-minute window)
    // ========================================================================

    /** H27: a timestamp far in the past (>5 min) is rejected even with a correct v1. */
    @Test
    void expiredStripeSignatureIsRejected() {
        long expiredTs = java.time.Instant.now().getEpochSecond() - 600L;  // 10 min ago
        byte[] utf8Body = "{\"x\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        String signedPayload = expiredTs + "." + new String(utf8Body, StandardCharsets.UTF_8);
        String hex = hmacHex(SECRET, signedPayload.getBytes(StandardCharsets.UTF_8));
        String header = stripeHeader(expiredTs, hex);

        assertFalse(WebhookSignatureVerifier.verify(SECRET, utf8Body, header, expiredTs));
    }

    /** H27: a timestamp far in the future (>5 min) is rejected even with a correct v1. */
    @Test
    void futureStripeSignatureIsRejected() {
        long futureTs = java.time.Instant.now().getEpochSecond() + 600L;  // 10 min ahead
        byte[] utf8Body = "{\"x\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        String signedPayload = futureTs + "." + new String(utf8Body, StandardCharsets.UTF_8);
        String hex = hmacHex(SECRET, signedPayload.getBytes(StandardCharsets.UTF_8));
        String header = stripeHeader(futureTs, hex);

        assertFalse(WebhookSignatureVerifier.verify(SECRET, utf8Body, header, futureTs));
    }

    /**
     * H27: replay protection is the timestamp window itself — the same
     * header sent twice within the 5-min window is currently accepted (Stripe's
     * contract). Beyond the window, replays are rejected. Asserts the
     * contract on both sides.
     */
    @Test
    void replayWithinWindowIsAcceptedBeyondWindowIsRejected() {
        long now = java.time.Instant.now().getEpochSecond();
        byte[] utf8Body = "{\"x\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        String signedPayload = now + "." + new String(utf8Body, StandardCharsets.UTF_8);
        String hex = hmacHex(SECRET, signedPayload.getBytes(StandardCharsets.UTF_8));
        String header = stripeHeader(now, hex);

        // First send — within window
        assertTrue(WebhookSignatureVerifier.verify(SECRET, utf8Body, header, now));

        // Replay after window — verifier no longer trusts the timestamp
        long wayLater = now + 600L;
        assertFalse(WebhookSignatureVerifier.verify(SECRET, utf8Body, header, wayLater));
    }
}
