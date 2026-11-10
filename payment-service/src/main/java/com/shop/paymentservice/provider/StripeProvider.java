package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnExpression("'${shop.payment.provider:}'.equalsIgnoreCase('stripe') || T(org.springframework.util.StringUtils).hasText('${shop.payment.stripe.secret-key:}')")
@Slf4j
public class StripeProvider implements PaymentProvider {

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
            PaymentIntent original = findIntentByLocalPaymentId(paymentId);
            if (original == null) {
                return new ProviderResult("stripe-error-intent-not-found", false);
            }

            String refundKey = "refund-" + idempotencyKey + "-" + amount.toPlainString();
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(original.getId())
                    .setAmount(toMinorUnits(amount, original.getCurrency()))
                    .build();
            Refund refund = Refund.create(params, stripeOptions(refundKey));
            return new ProviderResult(refund.getId(), true);
        } catch (IdempotencyException e) {
            log.info("Stripe replayed cached refund for idempotency key {} — treating as accepted", idempotencyKey);
            return new ProviderResult("idempotent-" + idempotencyKey, true);
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
     * way back is the Stripe Search API (no 24h idempotency-cache race).
     */
    private PaymentIntent findIntentByLocalPaymentId(UUID paymentId) throws StripeException {
        PaymentIntentSearchParams searchParams = PaymentIntentSearchParams.builder()
                .setQuery("metadata['payment_id']:'" + paymentId + "'")
                .setLimit(1L)
                .build();
        return PaymentIntent.search(searchParams, RequestOptions.builder()
                .setApiKey(properties.secretKey())
                .build())
            .getData().stream().findFirst().orElse(null);
    }

    private RequestOptions stripeOptions(String idempotencyKey) {
        return RequestOptions.builder()
                .setApiKey(properties.secretKey())
                .setIdempotencyKey(idempotencyKey)
                .build();
    }

    static long toMinorUnits(BigDecimal amount, String currency) {
        return StripeCurrencyUnits.toMinor(amount, currency);
    }
}
