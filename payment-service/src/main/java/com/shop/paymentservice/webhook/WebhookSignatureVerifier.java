package com.shop.paymentservice.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Webhook signature verifier for the payment fleet.
 *
 * <h3>H8 + H27 — Stripe-style signature scheme</h3>
 *
 * Two on-the-wire shapes are accepted:
 * <ol>
 *   <li><b>Stripe-style header</b> — {@code "t=<unix_seconds>,v1=<hex_hmac>"}.
 *       The signed payload is {@code "<t>.<raw_body>"} and {@code v1} is the
 *       lowercase hex HMAC-SHA256 of that string under the webhook secret. This
 *       is the canonical shape Stripe ships — and the fleet uses the same shape
 *       for its non-Stripe providers so a single verifier covers the fleet.</li>
 *   <li><b>Bare 64-char lowercase hex</b> — the legacy HMAC over {@code raw_body}
 *       alone. Kept because {@code sha256=} prefix is optional per the plan (the
 *       {@code sha256=} prefix is the bug being fixed — anything other than the
 *       two accepted shapes above is rejected).</li>
 * </ol>
 *
 * <p>The Stripe-style variant <b>also</b> enforces H27 timestamp/replay
 * protection: when the supplied header carries a {@code t=...} component, the
 * verifier rejects the request unless {@code |now - t| <= TOLERANCE_SECONDS}
 * (default 5 min, overridable). The {@link #verify(String, byte[], String, Clock)}
 * overload lets a caller pin the clock in tests for deterministic expiry
 * scenarios without exposing the clock globally.</p>
 *
 * <p>Rejection is all-or-nothing — the verifier never partial-accepts a
 * malformed header. The compare is constant-time
 * ({@link MessageDigest#isEqual(byte[], byte[])}) to defeat timing oracles.</p>
 */
public final class WebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final long DEFAULT_TOLERANCE_SECONDS = 300L;  // 5 min — Stripe default

    private WebhookSignatureVerifier() {
    }

    /**
     * Back-compat entrypoint — clock-based timestamp tolerance is enforced
     * when the supplied header carries a {@code t=} component. Uses
     * {@link Clock#systemUTC()} for the now comparison.
     */
    public static boolean verify(String secret, byte[] rawBody, String signatureHeader) {
        return verify(secret, rawBody, signatureHeader, Clock.systemUTC());
    }

    /**
     * Back-compat entrypoint with an explicit timestamp (epoch seconds) — used
     * by callers that have already parsed the {@code t=} component off the
     * header and want the verifier to apply H27 tolerance against that
     * declared instant. {@code -1} means "no timestamp supplied" and skips the
     * tolerance check (the bare-hex branch).
     */
    public static boolean verify(String secret, byte[] rawBody, String signatureHeader, long declaredTimestampSeconds) {
        return verifyInternal(secret, rawBody, signatureHeader, declaredTimestampSeconds, Clock.systemUTC());
    }

    /**
     * Test seam — caller pins the clock so expiry / replay cases are
     * deterministic.
     */
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

        // Branch 1 — Stripe-style header: "t=<ts>,v1=<hex>"
        StripeHeader stripe = parseStripeHeader(signatureHeader);
        if (stripe != null) {
            // H27 — replay protection (only enforced when the header carries a t= component).
            // We trust the wire timestamp over the caller's declared value when both exist
            // because the wire is the attacker-controlled surface.
            long wireTs = stripe.timestampSeconds;
            if (!withinTolerance(wireTs, clock)) {
                return false;
            }
            // If the caller also passed a declaredTimestamp, it must match the wire (defence in depth).
            if (declaredTimestampSeconds >= 0 && declaredTimestampSeconds != wireTs) {
                return false;
            }
            String signedPayload = wireTs + "." + new String(rawBody, StandardCharsets.UTF_8);
            byte[] provided = HexFormat.of().parseHex(stripe.v1Hex);
            return constantTimeEquals(provided, hmac(secret, signedPayload));
        }

        // Branch 2 — bare 64-char lowercase hex (legacy HMAC over rawBody).
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

    /**
     * Parse {@code "t=<long>,v1=<hex>"} or {@code "t=<long>,v1=<hex>,..."} (extra
     * schemes appended by the provider are tolerated — only the {@code t} and
     * {@code v1} components are consumed). Returns {@code null} when the
     * header doesn't carry the Stripe scheme — caller then tries the bare-hex
     * branch.
     */
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
                    return null;  // malformed t= component — reject the whole header
                }
            } else if ("v1".equals(key)) {
                if (value.length() != SHA256_HEX_LENGTH) {
                    return null;  // v1 must be the full 64-char hex
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
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable — JRE is misconfigured", ex);
        }
    }

    private static byte[] hmacBytes(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable — JRE is misconfigured", ex);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private record StripeHeader(long timestampSeconds, String v1Hex) {
    }
}
