package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.net.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * C5 Task 4 — {@code GET /actuator/health/payment-stripe} (mock provider has
 * no equivalent). UP requires a live Stripe API round-trip
 * ({@code Balance.retrieve()}); any StripeException → DOWN. The Stripe
 * account id rides along as a detail.
 */
class StripeHealthIndicatorTest {

    private MockedStatic<Balance> balanceStatic;
    private MockedStatic<Account> accountStatic;

    private StripeHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        balanceStatic = mockStatic(Balance.class);
        accountStatic = mockStatic(Account.class);
        indicator = new StripeHealthIndicator(new PaymentStripeProperties(
                "sk_test_123", "whsec_123", "2024-06-20", false));
    }

    @AfterEach
    void tearDown() {
        balanceStatic.close();
        accountStatic.close();
    }

    private Balance balance(Boolean livemode) {
        Balance balance = new Balance();
        balance.setObject("balance");
        balance.setLivemode(livemode);
        return balance;
    }

    @Test
    void stripeReachable_reportsUpWithAccountId() throws Exception {
        balanceStatic.when(() -> Balance.retrieve(any(RequestOptions.class)))
                .thenReturn(balance(false));
        Account account = new Account();
        account.setId("acct_test_123");
        accountStatic.when(() -> Account.retrieve(any(RequestOptions.class))).thenReturn(account);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("stripeAccountId", "acct_test_123")
                .containsEntry("livemode", false);
    }

    @Test
    void stripeUnreachable_reportsDown() throws Exception {
        balanceStatic.when(() -> Balance.retrieve(any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("network down"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void balanceReachableButAccountLookupFails_staysUpWithoutAccountId() throws Exception {
        // The balance probe is the liveness signal; the account id is
        // best-effort detail and must not flip the indicator on its own.
        balanceStatic.when(() -> Balance.retrieve(any(RequestOptions.class)))
                .thenReturn(balance(true));
        accountStatic.when(() -> Account.retrieve(any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("network down"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("stripeAccountId");
        assertThat(health.getDetails()).containsEntry("livemode", true);
    }

    @Test
    void healthProbesAreCachedWithinTtl() throws Exception {
        // Health checks can fire every few seconds — every probe must NOT be
        // a Stripe API call (rate limits). One call serves probes within TTL.
        balanceStatic.when(() -> Balance.retrieve(any(RequestOptions.class)))
                .thenReturn(balance(false));
        Account account = new Account();
        account.setId("acct_test_123");
        accountStatic.when(() -> Account.retrieve(any(RequestOptions.class))).thenReturn(account);

        indicator.health();
        indicator.health();
        indicator.health();

        balanceStatic.verify(() -> Balance.retrieve(any(RequestOptions.class)),
                org.mockito.Mockito.times(1));
        accountStatic.verify(() -> Account.retrieve(any(RequestOptions.class)),
                org.mockito.Mockito.times(1));
    }

    @Test
    void expiredCacheProbesStripeAgain() throws Exception {
        balanceStatic.when(() -> Balance.retrieve(any(RequestOptions.class)))
                .thenReturn(balance(false));
        Account account = new Account();
        account.setId("acct_test_123");
        accountStatic.when(() -> Account.retrieve(any(RequestOptions.class))).thenReturn(account);

        indicator.health();
        indicator.forceCacheExpiryForTest();
        indicator.health();

        balanceStatic.verify(() -> Balance.retrieve(any(RequestOptions.class)),
                org.mockito.Mockito.times(2));
    }
}
