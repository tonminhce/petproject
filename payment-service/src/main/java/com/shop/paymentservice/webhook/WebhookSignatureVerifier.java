package com.shop.paymentservice.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final long DEFAULT_TOLERANCE_SECONDS = 300L;

    private WebhookSignatureVerifier() {
    }

    public static boolean verify(String secret, byte[] rawBody, String signatureHeader) {
        return verify(secret, rawBody, signatureHeader, Clock.systemUTC());
    }

    public static boolean verify(String secret, byte[] rawBody, String signatureHeader, long declaredTimestampSeconds) {
        return verifyInternal(secret, rawBody, signatureHeader, declaredTimestampSeconds, Clock.systemUTC());
    }

    public static boolean verify(String secret, byte[] rawBody, String signatureHeader, Clock clock) {
        return verifyInternal(secret, rawBody, signatureHeader, -1L, clock);
    }

    private static boolean verifyInternal(String secret, byte[] rawBody, String signatureHeader,
                                          long declaredTimestampSeconds, Clock clock) {
        if (secret == null || secret.isBlank() || rawBody == null) {
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        StripeHeader stripe = parseStripeHeader(signatureHeader);
        if (stripe != null) {

            long wireTs = stripe.timestampSeconds;
            if (!withinTolerance(wireTs, clock)) {
                return false;
            }

            if (declaredTimestampSeconds >= 0 && declaredTimestampSeconds != wireTs) {
                return false;
            }
            String signedPayload = wireTs + "." + new String(rawBody, StandardCharsets.UTF_8);

            byte[] provided;
            try {
                provided = HexFormat.of().parseHex(stripe.v1Hex);
            } catch (IllegalArgumentException ex) {
                log.debug("Rejected webhook signature with a non-hex v1 component");
                return false;
            }
            return constantTimeEquals(provided, hmac(secret, signedPayload));
        }

        if (signatureHeader.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        try {
            byte[] provided = HexFormat.of().parseHex(signatureHeader);
            return constantTimeEquals(provided, hmacBytes(secret, rawBody));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static StripeHeader parseStripeHeader(String header) {
        if (header.indexOf("t=") != 0) {
            return null;
        }
        Long timestamp = null;
        String v1 = null;
        for (String pair : header.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if ("t".equals(key)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException nfe) {
                    return null;
                }
            } else if ("v1".equals(key)) {
                if (value.length() != SHA256_HEX_LENGTH) {
                    return null;
                }
                v1 = value;
            }
        }
        if (timestamp == null || v1 == null) {
            return null;
        }
        return new StripeHeader(timestamp, v1);
    }

    private static boolean withinTolerance(long wireTimestampSeconds, Clock clock) {
        long now = Instant.now(clock).getEpochSecond();
        long delta = Math.abs(now - wireTimestampSeconds);
        return delta <= DEFAULT_TOLERANCE_SECONDS;
    }

    private static byte[] hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable — JRE is misconfigured", ex);
        }
    }

    private static byte[] hmacBytes(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable — JRE is misconfigured", ex);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private record StripeHeader(long timestampSeconds, String v1Hex) {
    }
}
