package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnExpression("'${shop.payment.provider:}'.equalsIgnoreCase('stripe') || T(org.springframework.util.StringUtils).hasText('${shop.payment.stripe.secret-key:}')")
@Slf4j
public class StripeProvider implements PaymentProvider {

    private final PaymentStripeProperties properties;
    private final StripeClient stripeClient;

    public StripeProvider(PaymentStripeProperties properties) {
        if (properties == null || properties.secretKey() == null || properties.secretKey().isBlank()) {
            throw new IllegalStateException(
                    "StripeProvider requires shop.payment.stripe.secret-key env (SHOP_PAYMENT_STRIPE_SECRET_KEY)");
        }
        this.properties = properties;
        this.stripeClient = buildClient(properties);
    }

    private static StripeClient buildClient(final PaymentStripeProperties properties) {
        if (properties.apiBase().isBlank()) {
            return null;
        }
        return StripeClient.builder()
                .setApiKey(properties.secretKey())
                .setApiBase(properties.apiBase())
                .build();
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
        long minorUnits = toMinorUnits(amount, currency);
        PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                .setAmount(minorUnits)
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .putMetadata("payment_id", paymentId.toString())
                .putMetadata("idempotency_key", idempotencyKey);

        if (properties.autoConfirmSandbox() && properties.isSandbox()) {
            paramsBuilder.setPaymentMethod(properties.sandboxPaymentMethod())
                    .setConfirm(true)
                    .setReturnUrl(properties.returnUrl());
        } else {
            // Stripe rejects a return url on an intent that is not confirmed yet, so the url travels
            // with the response instead and the client hands it to the confirmation call.
            paramsBuilder.setConfirm(false)
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(properties.allowRedirects()
                                    ? PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.ALWAYS
                                    : PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                            .build());
        }
        PaymentIntentCreateParams params = paramsBuilder.build();
        try {
            PaymentIntent intent = createIntent(params, idempotencyKey);
            return captureOutcome(intent, minorUnits, currency);
        } catch (IdempotencyException e) {
            log.info("Stripe replayed a cached intent for idempotency key {} - the capture outcome is unknown",
                    idempotencyKey);
            return ProviderResult.indeterminate("idempotency-conflict");
        } catch (CardException e) {
            log.warn("Stripe card decline (paymentId={}, code={}, declineCode={})",
                    paymentId, e.getCode(), e.getDeclineCode());
            return ProviderResult.rejected(e.getCode() == null ? "card-error" : e.getCode());
        } catch (ApiConnectionException e) {
            log.error("Stripe connection failure on capture (paymentId={}) - the capture outcome is unknown",
                    paymentId, e);
            return ProviderResult.indeterminate("stripe-error-" + e.getClass().getSimpleName());
        } catch (StripeException e) {
            log.error("Stripe API error on capture (paymentId={}, code={}, requestId={})",
                    paymentId, e.getCode(), e.getRequestId(), e);
            String eventId = "stripe-error-" + (e.getCode() == null ? e.getClass().getSimpleName() : e.getCode());
            return isDefinitiveClientError(e)
                    ? ProviderResult.rejected(eventId)
                    : ProviderResult.indeterminate(eventId);
        }
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return refund(paymentId, amount, currency, idempotencyKey, null);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey,
            String providerIntentId) {
        try {
            PaymentIntent original = resolveIntent(paymentId, providerIntentId);
            if (original == null) {
                return ProviderResult.rejected("stripe-error-intent-not-found");
            }
            if (!sameCurrency(currency, original.getCurrency())) {
                log.warn("Stripe refund refused: payment currency {} differs from the original transaction currency {}",
                        currency, original.getCurrency());
                return ProviderResult.rejected("currency-mismatch");
            }
            long refundMinorUnits = toMinorUnits(amount, currency);
            String refundKey = idempotencyKey;
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(original.getId())
                    .setAmount(refundMinorUnits)
                    .build();
            Refund refund = createRefund(params, refundKey);
            return refundOutcome(refund, refundMinorUnits, currency);
        } catch (IdempotencyException e) {
            log.info("Stripe replayed a cached refund for idempotency key {} - the refund outcome is unknown",
                    idempotencyKey);
            return ProviderResult.indeterminate("idempotency-conflict");
        } catch (CardException e) {
            log.warn("Stripe declined the refund (paymentId={}, code={})", paymentId, e.getCode());
            return ProviderResult.rejected(e.getCode() == null ? "card-error" : e.getCode());
        } catch (ApiConnectionException e) {
            log.error("Stripe connection failure on refund (paymentId={}) - the refund outcome is unknown",
                    paymentId, e);
            return ProviderResult.indeterminate("stripe-error-" + e.getClass().getSimpleName());
        } catch (StripeException e) {
            log.error("Stripe API error on refund (paymentId={}, code={}, requestId={})",
                    paymentId, e.getCode(), e.getRequestId(), e);
            String eventId = "stripe-error-" + (e.getCode() == null ? e.getClass().getSimpleName() : e.getCode());
            return isDefinitiveClientError(e)
                    ? ProviderResult.rejected(eventId)
                    : ProviderResult.indeterminate(eventId);
        }
    }

    private ProviderResult captureOutcome(PaymentIntent intent, long expectedMinorUnits, String currency) {
        String intentId = intent.getId();
        String status = intent.getStatus() == null ? "" : intent.getStatus().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "succeeded" -> confirmedCapture(intent, intentId, expectedMinorUnits, currency);
            case "requires_payment_method", "requires_confirmation", "requires_action" ->
                    ProviderResult.requiresConfirmation(intentId, intent.getClientSecret(), properties.returnUrl());
            case "canceled" -> ProviderResult.rejected("intent-canceled");
            default -> ProviderResult.indeterminate("intent-status-" + intent.getStatus());
        };
    }

    private ProviderResult confirmedCapture(PaymentIntent intent, String intentId, long expectedMinorUnits,
                                            String currency) {
        Long reportedAmount = intent.getAmount();
        if (reportedAmount == null || reportedAmount.longValue() != expectedMinorUnits) {
            return ProviderResult.rejected("amount-mismatch");
        }
        String reportedCurrency = intent.getCurrency();
        if (reportedCurrency == null || !reportedCurrency.equalsIgnoreCase(currency)) {
            return ProviderResult.rejected("currency-mismatch");
        }
        return ProviderResult.confirmed(intentId);
    }

    private ProviderResult refundOutcome(Refund refund, long expectedMinorUnits, String expectedCurrency) {
        String status = refund.getStatus() == null ? "" : refund.getStatus().toLowerCase(Locale.ROOT);
        if ("succeeded".equals(status)) {
            Long reportedAmount = refund.getAmount();
            if (reportedAmount == null || reportedAmount.longValue() != expectedMinorUnits) {
                return ProviderResult.rejected("amount-mismatch");
            }
            String reportedCurrency = refund.getCurrency();
            if (reportedCurrency != null && expectedCurrency != null
                    && !reportedCurrency.equalsIgnoreCase(expectedCurrency)) {
                return ProviderResult.rejected("currency-mismatch");
            }
            return ProviderResult.confirmed(refund.getId());
        }
        if ("failed".equals(status) || "canceled".equals(status)) {
            return ProviderResult.rejected("refund-" + status);
        }
        return ProviderResult.indeterminate("refund-status-" + refund.getStatus());
    }

    private PaymentIntent resolveIntent(UUID paymentId, String providerIntentId) throws StripeException {
        if (providerIntentId != null && !providerIntentId.isBlank()) {
            return retrieveIntent(providerIntentId);
        }
        return findIntentByLocalPaymentId(paymentId);
    }

    private PaymentIntent createIntent(PaymentIntentCreateParams params, String idempotencyKey)
            throws StripeException {
        RequestOptions options = stripeOptions(idempotencyKey);
        return stripeClient == null
                ? PaymentIntent.create(params, options)
                : stripeClient.paymentIntents().create(params, options);
    }

    private Refund createRefund(RefundCreateParams params, String idempotencyKey) throws StripeException {
        RequestOptions options = stripeOptions(idempotencyKey);
        return stripeClient == null
                ? Refund.create(params, options)
                : stripeClient.refunds().create(params, options);
    }

    private PaymentIntent retrieveIntent(String intentId) throws StripeException {
        RequestOptions options = RequestOptions.builder().setApiKey(properties.secretKey()).build();
        return stripeClient == null
                ? PaymentIntent.retrieve(intentId, options)
                : stripeClient.paymentIntents().retrieve(intentId, options);
    }

    private PaymentIntent searchIntents(PaymentIntentSearchParams searchParams) throws StripeException {
        RequestOptions options = RequestOptions.builder().setApiKey(properties.secretKey()).build();
        return stripeClient == null
                ? PaymentIntent.search(searchParams, options).getData().stream().findFirst().orElse(null)
                : stripeClient.paymentIntents().search(searchParams, options).getData().stream()
                        .findFirst().orElse(null);
    }

    private PaymentIntent findIntentByLocalPaymentId(UUID paymentId) throws StripeException {
        PaymentIntentSearchParams searchParams = PaymentIntentSearchParams.builder()
                .setQuery("metadata['payment_id']:'" + paymentId + "'")
                .setLimit(1L)
                .build();
        return searchIntents(searchParams);
    }

    static boolean sameCurrency(String expected, String reported) {
        return expected != null && !expected.isBlank()
                && reported != null && !reported.isBlank()
                && expected.equalsIgnoreCase(reported);
    }

    private boolean isDefinitiveClientError(StripeException e) {
        Integer status = e.getStatusCode();
        return status != null && status >= 400 && status < 500;
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
