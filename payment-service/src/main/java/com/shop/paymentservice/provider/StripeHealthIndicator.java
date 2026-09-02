package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.net.RequestOptions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * C5 Task 4 — {@code GET /actuator/health/payment-stripe} (bean name drives
 * the endpoint id). Pings the Stripe API via {@code Balance.retrieve()} —
 * UP/DOWN reflects real reachability, not config presence. The Stripe account
 * id ({@code Account.retrieve()}) rides along as best-effort detail: the
 * balance probe is the liveness signal, a failed account lookup never flips
 * the indicator on its own.
 *
 * <p>Probes are cached for {@link #CACHE_TTL_MS}: health checks fire every few
 * seconds and must not become a Stripe rate-limit vector. The mock provider
 * has no equivalent indicator (conditional on provider=stripe).</p>
 */
@Component("payment-stripeHealthIndicator")
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "stripe")
public class StripeHealthIndicator implements HealthIndicator {

    static final long CACHE_TTL_MS = 30_000L;

    private final PaymentStripeProperties properties;
    private volatile Health cached;
    private volatile long cachedAt;

    public StripeHealthIndicator(PaymentStripeProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health health = cached;
        long now = System.currentTimeMillis();
        if (health != null && now - cachedAt < CACHE_TTL_MS) {
            return health;
        }
        health = probeStripe();
        cached = health;
        cachedAt = now;
        return health;
    }

    /** Test seam — expires the probe cache so the next call hits Stripe again. */
    void forceCacheExpiryForTest() {
        cachedAt = 0L;
    }

    private Health probeStripe() {
        RequestOptions options = RequestOptions.builder().setApiKey(properties.secretKey()).build();
        try {
            Balance balance = Balance.retrieve(options);
            Health.Builder builder = Health.up()
                    .withDetail("livemode", balance.getLivemode());
            String accountId = retrieveAccountId(options);
            if (accountId != null) {
                builder.withDetail("stripeAccountId", accountId);
            }
            return builder.build();
        } catch (StripeException e) {
            return Health.down(e).build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }

    private String retrieveAccountId(RequestOptions options) {
        try {
            return Account.retrieve(options).getId();
        } catch (StripeException | RuntimeException e) {
            return null;
        }
    }
}
