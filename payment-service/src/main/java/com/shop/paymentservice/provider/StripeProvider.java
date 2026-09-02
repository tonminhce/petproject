package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.RefundCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * C5 Task 2 — real Stripe provider behind the existing {@link PaymentProvider}
 * port (spec D1: official stripe-java SDK, never raw HTTP). Activated only by
 * {@code SHOP_PAYMENT_PROVIDER=stripe}; fails fast at bean creation when the
 * secret key is missing (D10).
 *
 * <p>Mapping onto the binding port — the port's
 * {@link ProviderResult}{@code (providerEventId, accepted)} carries no
 * attempt-status or client_secret, so:</p>
 * <ul>
 *   <li>a created/replayed Stripe object (succeeded, requires_action,
 *       requires_confirmation, …) is {@code accepted=true} — the payment row
 *       stays PENDING and the lifecycle completes via webhook (C7 contract:
 *       the synchronous capture API never transitions state itself);</li>
 *   <li>a Stripe-side rejection (CardException, infrastructure error) is
 *       {@code accepted=false} with the decline/error code as
 *       {@code providerEventId} — PaymentServiceImpl surfaces PAY-5008.</li>
 * </ul>
 *
 * <p>Idempotency (spec D2): PaymentIntent.create reuses
 * {@code payments.idempotency_key} as Stripe's Idempotency-Key; refunds derive
 * {@code refund-{key}-{amount}} per refund attempt. A duplicate key replays
 * Stripe's cached original — accepted, never an error.</p>
 */
@Component
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "stripe")
@Slf4j
public class StripeProvider implements PaymentProvider {

    /**
     * Zero-decimal currencies per Stripe's docs — amounts travel as whole
     * units (VND is the V1 fleet currency; spec §8).
     */
    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF");

    private final PaymentStripeProperties properties;

    public StripeProvider(PaymentStripeProperties properties) {
        if (properties == null || properties.secretKey() == null || properties.secretKey().isBlank()) {
            throw new IllegalStateException(
                    "StripeProvider requires shop.payment.stripe.secret-key env (SHOP_PAYMENT_STRIPE_SECRET_KEY)");
        }
        this.properties = properties;
    }

    @PostConstruct
    void verifyConfiguration() {
        if (properties.isVersionDriftedFromSdk()) {
            log.warn("shop.payment.stripe.api-version={} drifts from the stripe-java SDK wire version {} — "
                            + "the SDK constant governs the wire (spec D1); align the property or upgrade the SDK",
                    properties.apiVersion(), com.stripe.Stripe.API_VERSION);
        }
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(amount, currency))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                // D3 — SCA: intent is created UNCONFIRMED; Stripe.js on the
                // client confirms with the returned client_secret (storefront
                // Phase 9). The lifecycle completes via payment_intent.* webhook.
                .setConfirm(false)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build())
                .putMetadata("payment_id", paymentId.toString())
                .putMetadata("idempotency_key", idempotencyKey)
                .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params, stripeOptions(idempotencyKey));
            return new ProviderResult(intent.getId(), true);
        } catch (IdempotencyException e) {
            // D2 — duplicate Idempotency-Key: Stripe replays the cached
            // original intent; the adapter treats it as success, never an error.
            log.info("Stripe replayed cached intent for idempotency key {} — treating as accepted", idempotencyKey);
            return new ProviderResult("idempotent-" + idempotencyKey, true);
        } catch (CardException e) {
            log.warn("Stripe card decline (paymentId={}, code={}, declineCode={})",
                    paymentId, e.getCode(), e.getDeclineCode());
            return new ProviderResult(e.getCode() == null ? "card-error" : e.getCode(), false);
        } catch (StripeException e) {
            log.error("Stripe API error on capture (paymentId={}, code={}, requestId={})",
                    paymentId, e.getCode(), e.getRequestId(), e);
            return new ProviderResult("stripe-error-"
                    + (e.getCode() == null ? e.getClass().getSimpleName() : e.getCode()), false);
        }
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        try {
            PaymentIntent original = findIntentByLocalPaymentId(paymentId, idempotencyKey);
            if (original == null) {
                return new ProviderResult("stripe-error-intent-not-found", false);
            }
            // D2 — refund-attempt idempotency derives from the payment's key.
            String refundKey = "refund-" + idempotencyKey + "-" + amount.toPlainString();
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(original.getId())
                    .setAmount(toMinorUnits(amount, original.getCurrency()))
                    .build();
            Refund refund = Refund.create(params, stripeOptions(refundKey));
            return new ProviderResult(refund.getId(), true);
        } catch (StripeException e) {
            log.error("Stripe API error on refund (paymentId={}, code={}, requestId={})",
                    paymentId, e.getCode(), e.getRequestId(), e);
            return new ProviderResult("stripe-error-"
                    + (e.getCode() == null ? e.getClass().getSimpleName() : e.getCode()), false);
        }
    }

    /**
     * The port hands us only the LOCAL payment id — the Stripe intent id was
     * never persisted (no provider-reference column). The intent was created
     * with {@code metadata[payment_id]} at capture time, so the deterministic
     * way back is a metadata-filtered list (no 24h idempotency-cache race).
     */
    private PaymentIntent findIntentByLocalPaymentId(UUID paymentId, String idempotencyKey) throws StripeException {
        // stripe-java 24.x exposes no typed metadata filter on list params —
        // the metadata[key] query filter goes through putExtraParam.
        PaymentIntentListParams listParams = PaymentIntentListParams.builder()
                .setLimit(1L)
                .putExtraParam("metadata[payment_id]", paymentId.toString())
                .build();
        return PaymentIntent.list(listParams, stripeOptions(idempotencyKey))
                .getData().stream().findFirst().orElse(null);
    }

    private RequestOptions stripeOptions(String idempotencyKey) {
        return RequestOptions.builder()
                .setApiKey(properties.secretKey())
                .setIdempotencyKey(idempotencyKey)
                .build();
    }

    static long toMinorUnits(BigDecimal amount, String currency) {
        int digits = ZERO_DECIMAL_CURRENCIES.contains(
                currency == null ? "" : currency.toUpperCase(Locale.ROOT)) ? 0 : 2;
        return amount.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
