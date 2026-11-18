# Source Review — payment-service

**Repo:** `/Users/tonminh-mac/IdeaProjects/untitled5`
**Git revision reviewed:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f` (`git rev-parse HEAD` confirmed)
**Reviewer:** A8 (Payment Engineer)
**Scope:** `payment-service` — 36 main `.java`, 34 test `.java`, 9 Liquibase changelogs + `db.changelog-master.yaml` + `application.yml`
**Method:** every file listed in §1 was read in full with the `read` tool. No file was skipped. Git blob ids are `git rev-parse HEAD:<path>`. Claims below are quoted from source with `file:line`; no README, docs, or task notes were used as evidence.
**No payment call was executed.** This is a static review only.

---

## 1. File inventory

Line counts are `wc -l` (newline count). "Lines read" is the exact range consumed by the `read` tool. Every file was read from line 1 to EOF.

### 1.1 Main sources (36 files)

| # | Path | `git rev-parse HEAD:<path>` | Lines | Lines read |
|---|---|---|---|---|
| 1 | `payment-service/src/main/java/com/shop/paymentservice/PaymentServiceApplication.java` | `125889d8a0396c898e4b0ce851c9765080c82f5a` | 17 | 1–17 |
| 2 | `payment-service/src/main/java/com/shop/paymentservice/config/PaymentStripeProperties.java` | `cc17b09dd222e74207ad56a4d1fdd164f485c4c7` | 32 | 1–32 |
| 3 | `payment-service/src/main/java/com/shop/paymentservice/constant/PaymentStatus.java` | `d4a2fc003c8804b9d93d73200511eeae6d679f29` | 10 | 1–10 |
| 4 | `payment-service/src/main/java/com/shop/paymentservice/controller/BackofficePaymentController.java` | `f3ae748496037df2bb910dd1bfad987b7cdce393` | 47 | 1–47 |
| 5 | `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java` | `3712ce17d9881e5fd98234bdf6f5a5ec6ea2745b` | 96 | 1–96 |
| 6 | `payment-service/src/main/java/com/shop/paymentservice/dto/CreatePaymentRequest.java` | `3508e253ff0c43e7c6ca9c145c42e7a22c0bb534` | 27 | 1–27 |
| 7 | `payment-service/src/main/java/com/shop/paymentservice/dto/PaymentResponse.java` | `fd6c03035ce8692d9e506c8ac34ad4bd1176f4e8` | 34 | 1–34 |
| 8 | `payment-service/src/main/java/com/shop/paymentservice/entity/Payment.java` | `6ef53c26c518cc671bda932ba1efa79c0abd0c03` | 63 | 1–63 |
| 9 | `payment-service/src/main/java/com/shop/paymentservice/entity/PaymentEvent.java` | `b9edbed60985cb7f2c54fd8531490d9a3d9646f2` | 54 | 1–54 |
| 10 | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxEvent.java` | `df0cf95a2db8ce30ab6a970229048dacb09913a6` | 61 | 1–61 |
| 11 | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxEventRepository.java` | `3df60dd01acdadd547224b4ece66cdb3ed1e02ed` | 91 | 1–91 |
| 12 | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxRetentionScheduler.java` | `ac9a21a4e48ec54d2d6a212ff87175c4afbecc38` | 92 | 1–92 |
| 13 | `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentEventPublisher.java` | `57aeca128bee4b6ed78614ace4cfdd2e9c601c90` | 63 | 1–63 |
| 14 | `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentOutboxRelay.java` | `96c8abec3b972d24ba51d3ef7ef40a33aba78398` | 256 | 1–256 |
| 15 | `payment-service/src/main/java/com/shop/paymentservice/provider/CodProvider.java` | `1e47f36f4749965c41c88a35d318998d9962eb73` | 26 | 1–26 |
| 16 | `payment-service/src/main/java/com/shop/paymentservice/provider/MoMoProvider.java` | `9bd0cd62f498862514268c381253d6b56462c792` | 72 | 1–72 |
| 17 | `payment-service/src/main/java/com/shop/paymentservice/provider/MockProvider.java` | `9267be3422319400001e682918c166bf07535f44` | 26 | 1–26 |
| 18 | `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java` | `6ff8aa2669e68d01c303d4910ad0d848b4c8067e` | 16 | 1–16 |
| 19 | `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProviderConfig.java` | `938037d92f8fa3957b4f0d2da5b2281112cd1c68` | 54 | 1–54 |
| 20 | `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProviderFactory.java` | `0e5710e99317c7a63b8ccc45d2d7c8865db43218` | 60 | 1–60 |
| 21 | `payment-service/src/main/java/com/shop/paymentservice/provider/StripeCurrencyUnits.java` | `fceaed5968e1e8dd41290768d157e07879374132` | 35 | 1–35 |
| 22 | `payment-service/src/main/java/com/shop/paymentservice/provider/StripeHealthIndicator.java` | `240b3addd9c0c451be07c9da02681b2a9d08dde2` | 70 | 1–70 |
| 23 | `payment-service/src/main/java/com/shop/paymentservice/provider/StripeProvider.java` | `d26a4fb5a056509fc6212d961635870183e4cb9e` | 137 | 1–137 |
| 24 | `payment-service/src/main/java/com/shop/paymentservice/provider/VNPayProvider.java` | `a1afa4ab257ee9b0eafaf97c59aec601370a4551` | 74 | 1–74 |
| 25 | `payment-service/src/main/java/com/shop/paymentservice/repository/PaymentEventRepository.java` | `6ab3df6d108f51b7a14e4119ad272db22129733f` | 28 | 1–28 |
| 26 | `payment-service/src/main/java/com/shop/paymentservice/repository/PaymentRepository.java` | `b1dc7ae67913763deef0472abd97a2daeb41036b` | 40 | 1–40 |
| 27 | `payment-service/src/main/java/com/shop/paymentservice/scheduler/WebhookRetryScheduler.java` | `f0105020ff3b5da385ded9c03ef6cfbcfe55d4ba` | 129 | 1–129 |
| 28 | `payment-service/src/main/java/com/shop/paymentservice/service/PaymentService.java` | `135bf3dc29e12de6b6f6d159941697755a077a45` | 32 | 1–32 |
| 29 | `payment-service/src/main/java/com/shop/paymentservice/service/PaymentStateMachine.java` | `66081fbcc3f865a1f46e556279442ed74f2e65d6` | 34 | 1–34 |
| 30 | `payment-service/src/main/java/com/shop/paymentservice/service/PaymentWriter.java` | `d5a59220332ab96f4c154615d0263fbe709a527e` | 82 | 1–82 |
| 31 | `payment-service/src/main/java/com/shop/paymentservice/service/ReceiptService.java` | `32d60e126cbfdc3aa198ba47b74b8ca7f9a543f4` | 53 | 1–53 |
| 32 | `payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java` | `e99ac4ceaf363705379daad89d81225f8fe88fbc` | 400 | 1–400 |
| 33 | `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java` | `3d0ec902229c33644c5e0c941bce0219b0a72004` | 188 | 1–188 |
| 34 | `payment-service/src/main/java/com/shop/paymentservice/webhook/PaymentWebhookController.java` | `f621fa4f3aa22884b58802e41d198914753ec420` | 46 | 1–46 |
| 35 | `payment-service/src/main/java/com/shop/paymentservice/webhook/WebhookPayload.java` | `57b58d4cb2c935c8065c5a528b76f29164c3196c` | 20 | 1–20 |
| 36 | `payment-service/src/main/java/com/shop/paymentservice/webhook/WebhookSignatureVerifier.java` | `a6aeb523f9ec26796ff328651a76141614f9daa1` | 192 | 1–192 |

### 1.2 Test sources (34 files)

| # | Path | `git rev-parse HEAD:<path>` | Lines | Lines read |
|---|---|---|---|---|
| 37 | `payment-service/src/test/java/com/shop/paymentservice/CapturedPaymentLookupIT.java` | `a439dc4f6a93d5f0ff54b2b8887366c979a02e82` | 77 | 1–77 |
| 38 | `payment-service/src/test/java/com/shop/paymentservice/PaymentFlowIT.java` | `133cc0bea3f4768b7780b3b2f3232b24d957bd22` | 362 | 1–362 |
| 39 | `payment-service/src/test/java/com/shop/paymentservice/PaymentIdempotencyIT.java` | `eb92ab09afa249650aab50a480dec6b9117cce4a` | 140 | 1–140 |
| 40 | `payment-service/src/test/java/com/shop/paymentservice/StripeWebhookIT.java` | `6edd72211997baf1e0b80147e6ead7cdf436c732` | 379 | 1–379 |
| 41 | `payment-service/src/test/java/com/shop/paymentservice/config/PaymentStripePropertiesTest.java` | `998f07ce51d4c0c0b7281b8e0ddde3b2af8d2141` | 80 | 1–80 |
| 42 | `payment-service/src/test/java/com/shop/paymentservice/config/TestLiquibaseConfig.java` | `16dbd8b8a3dab12e566cce4f0a22f193af31c8e8` | 22 | 1–22 |
| 43 | `payment-service/src/test/java/com/shop/paymentservice/controller/BackofficePaymentControllerTest.java` | `ba5594150171b9d94503141b0c2fcfa7ba76ff87` | 198 | 1–198 |
| 44 | `payment-service/src/test/java/com/shop/paymentservice/controller/PaymentControllerAuditTest.java` | `92b216f7c02071a51a24f9191c458fb278116db8` | 132 | 1–132 |
| 45 | `payment-service/src/test/java/com/shop/paymentservice/controller/PaymentControllerTest.java` | `3931da07afa9273f4e3ea4a320ef30ad3db76396` | 376 | 1–376 |
| 46 | `payment-service/src/test/java/com/shop/paymentservice/outbox/OutboxRetentionSchedulerTest.java` | `9fab8ee28fb2676df8822deeb8e280033e448bb3` | 67 | 1–67 |
| 47 | `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxBatchAsyncIT.java` | `a797da2ace75b08ba512226155ac24f46e8ab211` | 94 | 1–94 |
| 48 | `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxClaimConcurrencyIT.java` | `3a4f8b432d27f8b624cfdc65e4901c1e31e94270` | 183 | 1–183 |
| 49 | `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxRelayOrderTest.java` | `1cbc9405c176e16d011fedb2bef9c50d15c0e342` | 70 | 1–70 |
| 50 | `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxRelayRecoveryIT.java` | `61207553f721f9bb395c6a900fcd569ef291be83` | 201 | 1–201 |
| 51 | `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxStrandedClaimIT.java` | `5b193c4e122d6266a6dafc2682c47c23b513aa76` | 125 | 1–125 |
| 52 | `payment-service/src/test/java/com/shop/paymentservice/provider/MoMoProviderTest.java` | `04c904e718b4878d00a617023ab27dd487759393` | 52 | 1–52 |
| 53 | `payment-service/src/test/java/com/shop/paymentservice/provider/MockProviderTest.java` | `d0922ce1ec0b770d9a890501f765032dc4ac8bec` | 46 | 1–46 |
| 54 | `payment-service/src/test/java/com/shop/paymentservice/provider/PaymentProviderConfigTest.java` | `9edb4f41dc464866fa32540939714c6cbc8d0181` | 120 | 1–120 |
| 55 | `payment-service/src/test/java/com/shop/paymentservice/provider/StripeHealthIndicatorTest.java` | `d2469df14b3c862eb6331ea4c05e177a6c5a1f55` | 125 | 1–125 |
| 56 | `payment-service/src/test/java/com/shop/paymentservice/provider/StripeProviderTest.java` | `b6bac33af5edd586402ba894bbccbc69338edf9d` | 259 | 1–259 |
| 57 | `payment-service/src/test/java/com/shop/paymentservice/provider/VNPayProviderTest.java` | `5228bc0943f1527f2f0ebd5bef7d0e2feb8dbdd0` | 63 | 1–63 |
| 58 | `payment-service/src/test/java/com/shop/paymentservice/repository/PaymentRepositoryIdempotencyScopingTest.java` | `c0affc1edc3b2fc13420e1b168b8003e36605e1d` | 54 | 1–54 |
| 59 | `payment-service/src/test/java/com/shop/paymentservice/scheduler/WebhookRetrySchedulerTest.java` | `a946bc1eedbe51588b440510a7e982ab08b3d0b6` | 132 | 1–132 |
| 60 | `payment-service/src/test/java/com/shop/paymentservice/service/PaymentStateMachineTest.java` | `16219d13d62f97aa6c108bb026ac29443a29b890` | 118 | 1–118 |
| 61 | `payment-service/src/test/java/com/shop/paymentservice/service/PaymentWriterTest.java` | `ae6764911c24ac5abcc1ef984a63431902a839db` | 105 | 1–105 |
| 62 | `payment-service/src/test/java/com/shop/paymentservice/service/ReceiptServiceTest.java` | `d091d2aaf937016b436a232a6f3c9fe0384036d0` | 108 | 1–108 |
| 63 | `payment-service/src/test/java/com/shop/paymentservice/service/WebhookEventServiceStripeTest.java` | `dc22a98618d6189cd4e281c7aeeb4efe7e4f2b76` | 462 | 1–462 |
| 64 | `payment-service/src/test/java/com/shop/paymentservice/service/WebhookEventServiceTest.java` | `629f677cc12052897f865f3e0b4324ab80f0ad94` | 351 | 1–351 |
| 65 | `payment-service/src/test/java/com/shop/paymentservice/service/impls/PaymentServiceImplTest.java` | `ffa43e7d585f6744babacba90daa14b5c4a8e02c` | 248 | 1–248 |
| 66 | `payment-service/src/test/java/com/shop/paymentservice/support/AbstractIntegrationTest.java` | `7f53824a78dc45a29d3a51b0c8500935e4de7587` | 224 | 1–224 |
| 67 | `payment-service/src/test/java/com/shop/paymentservice/support/PaymentBootstrapIT.java` | `ff649e2254ebd4f99c5f7d25cb817fe3a146e4df` | 55 | 1–55 |
| 68 | `payment-service/src/test/java/com/shop/paymentservice/support/WebhookRetryIndexIT.java` | `cac0cd98f9819569ad3a1cd38732da85dc1d72d5` | 44 | 1–44 |
| 69 | `payment-service/src/test/java/com/shop/paymentservice/webhook/PaymentWebhookControllerTest.java` | `29c82fc86c896d5c3d14a264dfe991464876a280` | 93 | 1–93 |
| 70 | `payment-service/src/test/java/com/shop/paymentservice/webhook/WebhookSignatureVerifierTest.java` | `ba253c08f45a6f5b8a1e78caa52c24d2d2d0d8c4` | 187 | 1–187 |

### 1.3 Configuration / schema (10 files)

| # | Path | `git rev-parse HEAD:<path>` | Lines | Lines read |
|---|---|---|---|---|
| 71 | `payment-service/src/main/resources/application.yml` | `6a6f801cdf618a2388ec3693f6ec5d244fe57828` | 67 | 1–67 |
| 72 | `payment-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `1d18ce0b796af770436a1da44f69522a01ea097a` | 25 | 1–25 |
| 73 | `payment-service/src/main/resources/db/changelog/changelog-001-payments.yaml` | `3c34c0198a710017c5f7589e6d252e0dfed35418` | 73 | 1–73 |
| 74 | `payment-service/src/main/resources/db/changelog/changelog-002-payment-outbox.yaml` | `a3e1f0753c35aabad0c76862e6531a032bb0551c` | 43 | 1–43 |
| 75 | `payment-service/src/main/resources/db/changelog/changelog-003-webhook-retry.yaml` | `e4bb5691d6c9985de26eaa6ba3ad6de589488aac` | 34 | 1–34 |
| 76 | `payment-service/src/main/resources/db/changelog/changelog-004-expand-events-status-check.yaml` | `e943d8379d52669cf25fdc1dbf4c37fc1d5be054` | 26 | 1–26 |
| 77 | `payment-service/src/main/resources/db/changelog/changelog-005-outbox-retention.yaml` | `2602249c631ca5682e999a5a021f0d8eecd4812f` | 30 | 1–30 |
| 78 | `payment-service/src/main/resources/db/changelog/changelog-006-payment-user-id.yaml` | `fb9882f9b83e09fc5ea05199ef319a4cab03e006` | 24 | 1–24 |
| 79 | `payment-service/src/main/resources/db/changelog/changelog-007-webhook-retry-index.yaml` | `546c048aa2bb21797f25d0e32a4206b1b50f5f96` | 19 | 1–19 |
| 80 | `payment-service/src/main/resources/db/changelog/changelog-008-partial-refund.yaml` | `6169f9e48dc05f52b4edaa443423089af8746f59` | 52 | 1–52 |

**Total: 80/80 files read in full. Zero silent skips.**

### 1.4 Supporting files consulted outside scope (for the cross-service contract only)

| Path | Blob | Lines | Lines read | Why |
|---|---|---|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/constants/OutboxStatus.java` | — | 21 | 1–21 | outbox status values |
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | — | grep only | grep `PAYMENT_/WEBHOOK_/AMOUNT_MISMATCH/REFUND_` | error-code → HTTP mapping |
| `utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java` | — | grep only | grep `PAYMENTS` | path constants |
| `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java` | — | 158 | 50–119 | how `public-paths` is applied |
| `utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java` | — | grep only | grep `requireCaller` | caller authorization |
| `order-service/src/main/java/com/shop/orderservice/client/PaymentServiceClient.java` | — | 113 | 1–113 | **the only production caller of payment-service** |
| `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleConsumer.java` | — | 44 | 1–44 | consumer of `shop.payment.lifecycle.v1` |
| `gateway-service/src/main/resources/application.yml` | — | 149 | 40–99 | gateway public-endpoints |

---

## 2. Payment provider abstraction

**Verdict: a real Strategy + Registry/Factory exists. It is genuinely present and Spring-wired. But the registry silently falls back instead of failing closed, and the "port" is too narrow for a real PSP.**

### 2.1 The port (interface)

`payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java:6-16`

```java
public interface PaymentProvider {

    String name();

    ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey);

    ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey);

    record ProviderResult(String providerEventId, boolean accepted) {
    }
}
```

### 2.2 The implementations (5)

| Provider | File:line | `name()` | Conditional? |
|---|---|---|---|
| `MockProvider` | `provider/MockProvider.java:10-25` | `"mock"` | none (always registered) |
| `CodProvider` | `provider/CodProvider.java:10-25` | `"COD"` | none (always registered) |
| `MoMoProvider` | `provider/MoMoProvider.java:17-59` | `"MOMO"` | none (always registered) |
| `VNPayProvider` | `provider/VNPayProvider.java:17-61` | `"VNPAY"` | none (always registered) |
| `StripeProvider` | `provider/StripeProvider.java:26-136` | `"stripe"` | `provider/StripeProvider.java:24` `@ConditionalOnExpression("'${shop.payment.provider:}'.equalsIgnoreCase('stripe') \|\| T(org.springframework.util.StringUtils).hasText('${shop.payment.stripe.secret-key:}')")` |

`provider/MockProvider.java:18-20` (the shape of every lightweight provider):

```java
    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return new ProviderResult("mock-" + UUID.randomUUID(), true);
    }
```

### 2.3 The registry / factory

`provider/PaymentProviderFactory.java:15-59` — a `ConcurrentHashMap<String, PaymentProvider>` keyed by `name().toUpperCase(Locale.ROOT)` (`:17`, `:23`, `:25`), built from the Spring-injected `List<PaymentProvider>` (`:20-28`). Selection:

```java
    public PaymentProvider getProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return getDefaultProvider();
        }
        PaymentProvider p = providers.get(providerName.trim().toUpperCase(Locale.ROOT));
        if (p == null) {
            return getDefaultProvider();
        }
        return p;
    }
```
`provider/PaymentProviderFactory.java:34-43`. **An unknown provider name silently yields the default provider** — see PAY-02.

`provider/PaymentProviderFactory.java:45-55` — `getDefaultProvider()` falls back to `providers.values().iterator().next()` (arbitrary `ConcurrentHashMap` iteration order) when the configured default is not registered.

### 2.4 Spring wiring

`provider/PaymentProviderConfig.java:38-53`:

```java
    @Bean
    @Primary
    public PaymentProvider primary(List<PaymentProvider> all,
            @Value("${shop.payment.provider:mock}") String defaultProviderName) {
        String normalized = defaultProviderName.toUpperCase(Locale.ROOT);
        return all.stream()
                .filter(p -> p.name() != null && p.name().toUpperCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(all.get(0));
    }

    @Bean
    public PaymentProviderFactory paymentProviderFactory(List<PaymentProvider> all,
            @Value("${shop.payment.provider:mock}") String defaultProviderName) {
        return new PaymentProviderFactory(all, defaultProviderName);
    }
```

`@Primary` picks the configured provider for direct injection; the factory holds all of them. The active default is `shop.payment.provider` (`application.yml:47`, default `mock`).

### 2.5 Per-request provider selection

`service/impls/PaymentServiceImpl.java:50-55`:

```java
    private PaymentProvider resolveProvider(String requestedProvider) {
        if (providerFactory != null && requestedProvider != null && !requestedProvider.isBlank()) {
            return providerFactory.getProvider(requestedProvider);
        }
        return defaultProvider;
    }
```

Two call sites:
* **Create** — `service/impls/PaymentServiceImpl.java:67` `PaymentProvider provider = resolveProvider(req.provider());` then the name is frozen on the row at `:75` `.provider(provider.name())`. `req.provider()` is a free-form string on the wire (`dto/CreatePaymentRequest.java:18 String provider`) with **no validation** — `CreatePaymentRequest` has no `@Pattern`/`@Size` on it.
* **Capture / Refund** — `service/impls/PaymentServiceImpl.java:126` and `:146` resolve from the **persisted** `payment.getProvider()`.

**No switch/if-chain on provider type exists anywhere.** The only conditional dispatch on provider identity is the single `if ("stripe".equals(provider))` in the webhook controller (`webhook/PaymentWebhookController.java:36`), which selects the *signature scheme*, not the PSP.

### 2.6 What the port cannot express

`ProviderResult` carries only `(providerEventId, accepted)`. There is no field for: the PSP client secret / redirect URL (needed by `VNPayProvider`/`MoMoProvider`), the provider's own state, a failure code, or "unknown outcome". `provider/VNPayProvider.java:47` builds `paymentUrl` and then throws it away at `:49` (`return new ProviderResult(vnpTxnRef, true)`). `provider/MoMoProvider.java:41-47` computes a signature and sends nothing. See PAY-15.

---

## 3. PaymentIntent creation (full flow)

### 3.1 Create (no provider call)

`service/impls/PaymentServiceImpl.java:57-87`:

```java
    @Transactional
    public PaymentResponse create(CreatePaymentRequest req) {
        Optional<Payment> existing = (req.userId() != null)
                ? repository.findByIdempotencyKeyAndUserId(req.idempotencyKey(), req.userId())
                : repository.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), req);
        }

        PaymentProvider provider = resolveProvider(req.provider());

        Payment payment = Payment.builder()
                .orderId(req.orderId())
                .userId(req.userId())
                .amount(req.amount())
                .currency(req.currency())
                .status(PaymentStatus.PENDING)
                .provider(provider.name())
                .idempotencyKey(req.idempotencyKey())
                .build();
```

The insert runs in its own transaction (`service/PaymentWriter.java:34-37`, `Propagation.REQUIRES_NEW`) so a lost race does not poison the caller's transaction; the unique index is the arbiter and the loser re-reads and replays (`service/impls/PaymentServiceImpl.java:80-86`).

**Amount construction:** the amount is taken verbatim from the request. There is **no server-side computation of the amount from the order** — payment-service never calls order-service. The only guard is bean validation on the DTO (`dto/CreatePaymentRequest.java:14`):

```java
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
```

**Minimum-charge guard:** `@DecimalMin("0.01")` — a request for `0.00` or `0.001` is rejected at the HTTP boundary with `ERR-0422-V` (asserted in `test/.../controller/PaymentControllerTest.java:280-294`). Note the guard is expressed in major units and is currency-blind: for a zero-decimal currency (VND/JPY/KRW …) `0.01` VND is below the PSP minimum but is accepted; for a two-decimal currency `0.01` is exactly the Stripe minimum.

**Currency construction:** the request currency is stored as-is (`entity/Payment.java:30-32`, `char(3)`), with no normalisation and no ISO-4217 whitelist (`dto/CreatePaymentRequest.java:15` `@NotBlank @Size(min = 3, max = 3) String currency`).

**Provider call:** none. `create` never touches a PSP — asserted in `test/.../StripeWebhookIT.java:357-360` ("Create never calls the provider — zero SDK calls so far").

### 3.2 Capture — the PaymentIntent creation

`service/impls/PaymentServiceImpl.java:119-137`:

```java
    @Override
    @Transactional
    public PaymentResponse capture(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, payment.getStatus());
        }
        PaymentProvider provider = resolveProvider(payment.getProvider());
        ProviderResult result =
                provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
        payment.setPreviousStatus(PaymentStatus.PENDING);
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = writer.save(payment);
        writer.publishLifecycleEvent(saved, "payment.captured.v1");
        return PaymentResponse.from(saved);
    }
```

The Stripe adapter, `provider/StripeProvider.java:52-82`:

```java
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
            ...
            return new ProviderResult("idempotent-" + idempotencyKey, true);
        } catch (CardException e) {
            ...
            return new ProviderResult(e.getCode() == null ? "card-error" : e.getCode(), false);
        } catch (StripeException e) {
            ...
            return new ProviderResult("stripe-error-"
                    + (e.getCode() == null ? e.getClass().getSimpleName() : e.getCode()), false);
        }
    }
```

* **Amount/currency:** `toMinorUnits` → `provider/StripeCurrencyUnits.java:18-21`:
  ```java
      public static long toMinor(BigDecimal amount, String currency) {
          int digits = minorUnitDigits(currency);
          return amount.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact();
      }
  ```
  Zero-decimal currencies are listed at `provider/StripeCurrencyUnits.java:10-12` (VND, JPY, KRW, …). Currency is lower-cased for the wire (`StripeProvider.java:56`).
* **Idempotency key passed to Stripe:** yes — `provider/StripeProvider.java:127-132`:
  ```java
      private RequestOptions stripeOptions(String idempotencyKey) {
          return RequestOptions.builder()
                  .setApiKey(properties.secretKey())
                  .setIdempotencyKey(idempotencyKey)
                  .build();
      }
  ```
  The key is the payment's own `idempotency_key` (`PaymentServiceImpl.java:128`), asserted in `test/.../provider/StripeProviderTest.java:106`. It is also mirrored into `metadata.idempotency_key` (`StripeProvider.java:62`).
* **Timeout / retry policy:** **none.** There is no Stripe connect/read timeout configuration anywhere in the module (`grep -rn "timeout" payment-service/src/main` returns only the outbox publish timeout at `outbox/PaymentOutboxRelay.java:83`). The stripe-java 24.24.0 default socket timeout applies. There is no retry, no backoff, no circuit breaker on the provider call. The call happens **inside** the `@Transactional` boundary of `PaymentServiceImpl.capture` (`:120` + `:127`), so the DB transaction and a pooled connection are held for the whole Stripe round-trip.
* **Provider failure mapping:**
  * `CardException` → `accepted=false`, code from `e.getCode()` → `PAY-5008` HTTP 502 (`ErrorCode.PAYMENT_PROVIDER_REJECTED`, `utils/common-core/.../ErrorCode.java:110`).
  * `IdempotencyException` → `accepted=true` with a **fabricated** event id `"idempotent-" + idempotencyKey` (`StripeProvider.java:67-71`).
  * Any other `StripeException` (including `ApiConnectionException`, i.e. a timeout) → `accepted=false` with `"stripe-error-<code>"` (`StripeProvider.java:76-81`).
  * `provider/StripeProvider.java:64-81` — the `catch` clauses cover `IdempotencyException`, `CardException`, `StripeException`. A non-`StripeException` `RuntimeException` would escape the adapter.

### 3.3 What happens on a Stripe timeout

**Yes — the PaymentIntent can be created at Stripe while the response is lost, and the service treats that as a definitive failure.**

Sequence:
1. `PaymentIntent.create(params, stripeOptions(idempotencyKey))` sends the request. Stripe creates the intent and commits it.
2. The response is lost (network timeout, LB reset, proxy cut). The SDK throws `ApiConnectionException` (a `StripeException`).
3. `provider/StripeProvider.java:76-81` catches it and returns `accepted=false`.
4. `service/impls/PaymentServiceImpl.java:129-131` throws `BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, …)` → HTTP **502 PAY-5008** to the caller.
5. The local row stays **PENDING** — nothing records that an intent may exist at Stripe. `ProviderResult.providerEventId` is discarded on failure, and on success it is **never persisted at all** (there is no provider-reference column on `payments`; the code says so at `provider/StripeProvider.java:110-115`).

Consequences:
* The caller sees "rejected" although the money may be reserved at Stripe. A later `payment_intent.succeeded` webhook will flip the row to CAPTURED (`service/WebhookEventService.java:199-211`), so the same payment is "rejected" to the caller and later "captured" in the DB.
* A retry of `POST /payments/{id}/capture` within Stripe's 24-hour idempotency window re-sends the **same** key, so Stripe replays the intent rather than creating a second one — that part is safe. After 24 hours the same key creates a **second** PaymentIntent, and the local status guard (`PaymentServiceImpl.java:123-125`) no longer blocks it because the row is not CAPTURED (it is PENDING) → a second, uncorrelated charge.
* The refund path has to *search* Stripe for the intent (`provider/StripeProvider.java:116-125`) using the Search API, which is eventually consistent — a refund issued right after the capture frequently finds nothing and returns `"stripe-error-intent-not-found"` (`StripeProvider.java:88-90`) → `PAY-5008` and **no money returned**. See PAY-08.

### 3.4 Refund

`service/impls/PaymentServiceImpl.java:139-157` — the full captured amount is refunded (`payment.getAmount()`), the status is set to REFUNDED, and `refunded_amount` is **not** touched (grep `setRefundedAmount` → only `WebhookEventService.java:205` and `:230`). The Stripe refund key is `"refund-" + idempotencyKey + "-" + amount.toPlainString()` (`provider/StripeProvider.java:92`), so two refunds of *different* amounts would both execute at Stripe.

---

## 4. Webhook handling

### 4.1 Entry point and raw-body preservation

`webhook/PaymentWebhookController.java:30-45`:

```java
    @PostMapping("/{provider}")
    public ApiResponse<Void> handle(
            @PathVariable String provider,
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature) {
        if ("stripe".equals(provider)) {
            webhookEventService.handleStripe(rawBody, stripeSignature);
            return ApiResponse.message("accepted");
        }
        if (!WebhookSignatureVerifier.verify(webhookSecret, rawBody, signature)) {
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        webhookEventService.handle(provider, rawBody);
        return ApiResponse.message("accepted");
    }
```

**Raw body IS preserved.** `@RequestBody byte[] rawBody` binds the raw bytes; no `String`/DTO binding, no `@RequestBody` → POJO conversion anywhere on this path. The signature is therefore computed over exactly the bytes Stripe/MoMo sent. This is correct and is asserted end-to-end in `test/.../StripeWebhookIT.java:296-310` (tampered body → 401, no row written) and `test/.../PaymentFlowIT.java:155-166`.

### 4.2 Signature verification — exact code

**Stripe path** (`service/WebhookEventService.java:82-95`) uses the official Stripe SDK:

```java
        Event event;
        try {
            event = Webhook.constructEvent(new String(rawBody, StandardCharsets.UTF_8),
                    signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        } catch (RuntimeException e) {
            ...
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
```

`Webhook.constructEvent` verifies `t=<unix>,v1=<hex>` with HMAC-SHA256 over `"<t>.<raw_body>"` and enforces the SDK default **300-second** tolerance; it fails closed on a blank secret (`service/WebhookEventService.java:75-81`).

**Non-Stripe path** (`webhook/WebhookSignatureVerifier.java:79-121`):

```java
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
            // not that it is hexadecimal. HexFormat.parseHex throws
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
```

Supporting members: `DEFAULT_TOLERANCE_SECONDS = 300L` (`:46`), `withinTolerance` (`:160-164`, `Math.abs(now - wireTs) <= 300`), `constantTimeEquals` = `MessageDigest.isEqual` (`:186-188`), `parseStripeHeader` (`:130-158`, rejects a malformed `t=`, requires a 64-char `v1`).

**Timestamp tolerance:** 300 s, **only on the Stripe-style header branch**. The bare-hex branch (`:112-120`) — which is what every non-Stripe provider actually uses today (`test/.../PaymentFlowIT.java:305-313` signs with a bare hex HMAC over the raw body) — has **no timestamp and therefore no replay window**.

### 4.3 Event-type switch

Stripe event types are mapped by a whitelist map (`service/WebhookEventService.java:56-59`):

```java
    private static final Map<String, String> STRIPE_EVENT_STATUS = Map.of(
            "payment_intent.succeeded", "CAPTURED",
            "payment_intent.payment_failed", "FAILED",
            "charge.refunded", "REFUNDED");
```

Unmapped types are stored as a PROCESSED no-op (`:96-101` → `storeProcessedNoOp` `:277-287`).

The carrier status is parsed by an explicit whitelist (`service/WebhookEventService.java:322-330`):

```java
    private PaymentStatus parseStatus(String status) {
        return switch (status == null ? "" : status) {
            case "CAPTURED" -> PaymentStatus.CAPTURED;
            case "PARTIALLY_REFUNDED" -> PaymentStatus.PARTIALLY_REFUNDED;
            case "FAILED" -> PaymentStatus.FAILED;
            case "REFUNDED" -> PaymentStatus.REFUNDED;
            default -> null;
        };
    }
```

`charge.refunded` is demoted to `PARTIALLY_REFUNDED` when `amount_refunded < amount` (`service/WebhookEventService.java:244-259`).

### 4.4 The idempotency store

`payment_events` table, `changelog-001-payments.yaml:42-73`:

```yaml
        - sql:
            sql: CREATE UNIQUE INDEX uk_payment_events_provider_event ON payment_events (provider, provider_event_id)
```
(`changelog-001-payments.yaml:72`)

Repository: `repository/PaymentEventRepository.java:20`

```java
    Optional<PaymentEvent> findFirstByProviderAndProviderEventId(String provider, String providerEventId);
```

Write path `service/WebhookEventService.java:105-133`:

```java
    public void handle(String provider, byte[] rawBody) {
        WebhookPayload payload = tryParse(rawBody);
        String providerEventId = resolveProviderEventId(payload, rawBody);
        ...
        Optional<PaymentEvent> existing = eventRepository
                .findFirstByProviderAndProviderEventId(provider, providerEventId);
        if (existing.isPresent() && PaymentEvent.STATUS_PROCESSED.equals(existing.get().getStatus())) {
            return;
        }
        if (existing.isEmpty()) {
            PaymentEvent event = buildEvent(provider, providerEventId, payload, rawBody);
            event.setStatus(PaymentEvent.STATUS_FAILED_RETRYABLE);
            event.setNextRetryAt(Instant.now());
            try {
                writer.insertEvent(event);
            } catch (DataIntegrityViolationException e) {
                log.debug("Webhook event already stored (provider={}, providerEventId={})", provider, providerEventId);
                return;
            }
        }
        ...
        processFromRaw(provider, providerEventId, rawBody);
    }
```

The event-id source is `service/WebhookEventService.java:351-357`:

```java
    private String resolveProviderEventId(WebhookPayload payload, byte[] rawBody) {
        if (payload != null && payload.providerEventId() != null && !payload.providerEventId().isBlank()) {
            return payload.providerEventId();
        }
        String prefix = payload == null ? UNPARSEABLE_PREFIX : INVALID_PREFIX;
        return prefix + sha256Hex(rawBody);
    }
```

For Stripe, the carrier payload is built at `service/WebhookEventService.java:236-275` with `.providerEventId(eventId)` where `eventId` is the Stripe event id (`evt_…`). So **the Stripe event id is persisted under a DB UNIQUE constraint** — see §7.

### 4.5 Mandatory race analysis

| # | Race | Actual behaviour | Verdict |
|---|---|---|---|
| R1 | **Duplicate webhook delivery** (Stripe re-delivers the same `evt_…`) | `handle()` finds the row with status `PROCESSED` → `return` at `service/WebhookEventService.java:113-115`. Zero second effect. Asserted in `test/.../StripeWebhookIT.java:312-330` and `test/.../PaymentFlowIT.java:134-151`. | **SAFE** |
| R2 | **Two concurrent identical webhooks** | Both threads read `existing` = empty (`:111-112`), both build an event, both call `writer.insertEvent`. One wins; the loser gets `DataIntegrityViolationException` → caught at `:122-125` → **`return` without processing**. The winner then calls `processFromRaw` (`:132`). The loser's transaction is aborted by the constraint violation, so only one processing path continues. **But** the loser returns *before* processing, so no duplicate event — however the winner and the loser can interleave differently: if the loser's insert *commits* first and the winner's insert then fails, the **winner** returns without processing and the **loser** proceeds — still exactly one process. The DB unique index is the real arbiter. Residual issue: the payment read-modify-write is not locked (§R7) and the state machine allows self-transitions (§5), so the *effect* of a second processing attempt is still non-idempotent. | **MOSTLY SAFE (DB-arbitrated), with the §5 self-transition caveat** |
| R3 | **Webhook arriving after order cancellation** | `process()` (`service/WebhookEventService.java:183-215`) never consults the order. `PaymentStateMachine.transition(PENDING, CAPTURED)` is legal (`service/PaymentStateMachine.java:13`). The payment becomes CAPTURED and `payment.captured.v1` is published. The order-service confirm gate then fails closed (`ORD-4012`), the order is auto-cancelled — **and the customer is charged**. payment-service has **no Kafka consumer at all** (`grep -rn "@KafkaListener" payment-service/src/main` → no hits), so it cannot learn about `order.cancelled` and cannot compensate with a refund. | **UNSAFE — no Saga compensation (PAY-06)** |
| R4 | **Webhook processed concurrently with the Kafka payment event** | payment-service is the *producer* of `shop.payment.lifecycle.v1` (`outbox/PaymentEventPublisher.java:23`, topic contract `utils/common-kafka/.../KafkaTopicContract.java:71`); the consumer is order-service (`order-service/.../PaymentLifecycleConsumer.java:21-28`). The overlap is: the REST capture path (`PaymentServiceImpl.capture` → `writer.publishLifecycleEvent(saved, "payment.captured.v1")`, `:135`) and the webhook path (`WebhookEventService.process` → `writer.completeWithEvent(..., outboxEventType(next))`, `:211`) both publish `payment.captured.v1` for the same payment. The outbox table has **no uniqueness on (aggregate_id, event_type)** — `changelog-002-payment-outbox.yaml:17` only makes `event_id` unique, and `event_id` is a fresh `UUID.randomUUID()` per publish (`outbox/PaymentEventPublisher.java:41`). Two rows are therefore published. order-service tolerates the duplicate (`PaymentLifecycleConsumer.java:35-41` catches `ORDER_INVALID_STATE_TRANSITION` and skips), but every other consumer of `payment.captured.v1` sees it twice. | **UNSAFE — duplicate lifecycle events (PAY-03)** |
| R5 | **Double refund** | Guarded on the local status: `PaymentServiceImpl.refund` requires `status == CAPTURED` (`:143-145`); the state machine allows `CAPTURED → REFUNDED` only once (after that `REFUNDED → REFUNDED` is a no-op self-transition and `REFUNDED → CAPTURED` is illegal, `service/PaymentStateMachine.java:12-17`, `:29-31`). At the PSP, the refund carries key `"refund-" + key + "-" + amount` (`provider/StripeProvider.java:92`), so a repeat of the same amount is replayed by Stripe. | **SAFE for the same amount.** **UNSAFE for different amounts**: the refund key embeds the amount, so two refunds of different amounts would both execute at Stripe — but the local guard blocks the second, so this is latent only. |
| R6 | **Refund racing with capture** | `PaymentServiceImpl.capture` requires `PENDING` (`:123-125`) and `refund` requires `CAPTURED` (`:143-145`), so the two cannot both succeed from a single consistent read. But both read the row **without a lock** and both are `@Transactional` with the default READ COMMITTED. Two concurrent requests can both read `CAPTURED` (refund) and one reads `PENDING` (capture) before either commits → the capture's `writer.save` and the refund's `writer.save` both write the row. `entity/Payment.java:59-62` declares `@Version private Long version`, so the second commit fails with `ObjectOptimisticLockingFailureException` — **which neither path catches** → HTTP 500, and the provider call may already have been made (the Stripe refund was already executed). | **UNSAFE — unhandled optimistic-lock failure after a side effect (PAY-12)** |
| R7 | **Two concurrent partial refunds (webhook)** | `applyPartialRefund` (`service/WebhookEventService.java:217-234`) is a read-modify-write of `refunded_amount` performed **outside any transaction** (`processFromRaw` `:157-176` has no `@Transactional`; the transaction only opens inside `writer.completeWithEvent`, `service/PaymentWriter.java:74-81`, *after* the mutation). Two concurrent partial refunds both read `already = 0`, both write `refunded_amount = X`; the optimistic lock turns the second into a rollback, which flips the event back to `FAILED_RETRYABLE` (`markFailedRetryable` `:289-293`) → the retry scheduler replays it → the replay succeeds but **publishes a second `payment.partially_refunded.v1`** (PAY-03). | **UNSAFE — duplicate lifecycle event on the retry path** |
| R8 | **Retry scheduler replayed by two pods** | `WebhookRetryScheduler.replay()` (`scheduler/WebhookRetryScheduler.java:61-74`) selects with a plain derived query `findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc` (`repository/PaymentEventRepository.java:26-27`) — **no `@Lock`, no `SKIP LOCKED`, no lease/claim column**. Two replicas will pick the same rows. Contrast with the outbox relay, which does it correctly (`outbox/OutboxEventRepository.java:35-37` `@Lock(PESSIMISTIC_WRITE)` + `jakarta.persistence.lock.timeout = -2` = `FOR UPDATE SKIP LOCKED`). | **UNSAFE — no claim on the retry queue (PAY-05)** |

---

## 5. Payment state machine

### 5.1 The status values

`constant/PaymentStatus.java:5-10`:

```java
public enum PaymentStatus {

    PENDING, CAPTURED, PARTIALLY_REFUNDED, FAILED, REFUNDED;

    public static final Set<PaymentStatus> TERMINAL_WEBHOOK_STATES = Set.of(CAPTURED, FAILED, REFUNDED);
}
```

DB constraint: `changelog-001-payments.yaml:34` `CHECK (status IN ('PENDING','CAPTURED','FAILED','REFUNDED'))`, widened by `changelog-008-partial-refund.yaml:36-42` to include `PARTIALLY_REFUNDED`. `TERMINAL_WEBHOOK_STATES` has **no production caller** (grep → declaration only) — dead constant.

### 5.2 The transition table

`service/PaymentStateMachine.java:12-33`:

```java
    private static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL_TRANSITIONS = Map.of(
            PaymentStatus.PENDING, Set.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED),
            PaymentStatus.CAPTURED, Set.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED),

            // (the self-transition is allowed) and by the final full refund.
            PaymentStatus.PARTIALLY_REFUNDED, Set.of(PaymentStatus.REFUNDED));

    public static PaymentStatus transition(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, from, to);
        }
        if (from == to) {
            return to;
        }
        if (!LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, from, to);
        }
        return to;
    }
```

### 5.3 The complete transition matrix

| From \ To | PENDING | CAPTURED | PARTIALLY_REFUNDED | FAILED | REFUNDED |
|---|---|---|---|---|---|
| **PENDING** | legal (self, `:26-28`) | legal (`:13`) | **illegal** | legal (`:13`) | **illegal** |
| **CAPTURED** | **illegal** | legal (self) | legal (`:14`) | **illegal** | legal (`:14`) |
| **PARTIALLY_REFUNDED** | **illegal** | **illegal** | legal (self) | **illegal** | legal (`:17`) |
| **FAILED** | **illegal** | **illegal** | **illegal** | legal (self) | **illegal** |
| **REFUNDED** | **illegal** | **illegal** | **illegal** | **illegal** | legal (self) |

`FAILED` and `REFUNDED` have no outgoing edges other than the self-transition. `PARTIALLY_REFUNDED → PARTIALLY_REFUNDED` is legal only via the `from == to` shortcut, not via the map.

### 5.4 What happens on an illegal transition

`BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, from, to)` → error code `PAY-5004`, HTTP **409 CONFLICT** (`utils/common-core/.../ErrorCode.java:106`).

**However — the state machine is only consulted on the webhook path.** `grep -rn "PaymentStateMachine" payment-service/src/main` → `service/WebhookEventService.java:199` and `:226` only. The REST capture and refund paths set the status **directly**:

* `service/impls/PaymentServiceImpl.java:132-133` `payment.setPreviousStatus(PaymentStatus.PENDING); payment.setStatus(PaymentStatus.CAPTURED);`
* `service/impls/PaymentServiceImpl.java:152-153` `payment.setPreviousStatus(PaymentStatus.CAPTURED); payment.setStatus(PaymentStatus.REFUNDED);`

They use ad-hoc `if (status != X) throw` guards (`:123-125`, `:143-145`) instead of `PaymentStateMachine.transition`. Two consequences: the invariant is duplicated in two places and can drift (it already has — the REST refund never sets `refunded_amount`, and it cannot express a partial refund), and the REST path has no notion of `PARTIALLY_REFUNDED` at all.

On the webhook path, an illegal transition is caught at `service/WebhookEventService.java:170-175` → `markFailedRetryable` → the event row is set to `FAILED_RETRYABLE` with `last_error` and the HTTP request is **still acked 200** (`webhook/PaymentWebhookController.java:44` returns `ApiResponse.message("accepted")`; the service swallows the exception). The event is then retried up to 6 times by `WebhookRetryScheduler` (`scheduler/WebhookRetryScheduler.java:40`, `:79-87`) and finally parked as `FAILED_PERMANENT` with a metric (`scheduler/WebhookRetryScheduler.java:58`).

**Illegal transition on the webhook path therefore does not change the payment state, does not notify the caller (200 OK), and is invisible until a metric fires.** Asserted in `test/.../service/WebhookEventServiceTest.java:244-256`.

---

## 6. Currency / amount consistency

### 6.1 Precision and scale

* All amounts are `java.math.BigDecimal` end-to-end: `dto/CreatePaymentRequest.java:14`, `entity/Payment.java:26-27` (`precision = 19, scale = 2`), `entity/Payment.java:55-57` (`refundedAmount`), `provider/PaymentProvider.java:10-12`, `dto/PaymentResponse.java:13`. **No `double`/`float` anywhere in payment-service** (grep: no `double` in a money field).
* DB columns are `numeric(19,2)` (`changelog-001-payments.yaml:17`, `changelog-008-partial-refund.yaml:49`).
* `@Digits(integer = 17, fraction = 2)` (`dto/CreatePaymentRequest.java:14`) rejects >2 fraction digits at the boundary.
* Rounding to minor units: `provider/StripeCurrencyUnits.java:18-21` uses `setScale(0, RoundingMode.HALF_UP)`. For a two-decimal currency the value is already exact; **for a zero-decimal currency a fractional amount is silently rounded** — `100.50 VND` becomes `101` minor units. The webhook then reports `101`, the amount check at `service/WebhookEventService.java:195-198` compares it against the stored `100.50` → `AMOUNT_MISMATCH` → the payment can never be captured. PAY-17.
* Inbound conversion: `provider/StripeCurrencyUnits.java:24-29` `BigDecimal.valueOf(minor, minorUnitDigits(currency))` — exact, scale-free. The comparison in `service/WebhookEventService.java:196` uses `compareTo`, so scale differences are irrelevant (asserted in `test/.../service/WebhookEventServiceTest.java:342-350`).

### 6.2 Currency code validation

**None beyond length.** `dto/CreatePaymentRequest.java:15` `@NotBlank @Size(min = 3, max = 3) String currency`. There is no ISO-4217 whitelist, no uppercase normalisation on write, and no currency in the supported set. Consequences:
* `"usd"` and `"USD"` are two different stored values (`entity/Payment.java:30-32` `char(3)`), and the idempotency payload comparison at `service/impls/PaymentServiceImpl.java:110-117` is case-sensitive → the same logical request with a different case is a `PAY-5003` conflict.
* The webhook amount check **never compares the currency** (`service/WebhookEventService.java:195-198` compares the amount only), so a payload in a different currency with the same numeric amount passes.
* `StripeProvider.java:56` lower-cases for the wire; `StripeCurrencyUnits.minorUnitDigits` upper-cases internally (`provider/StripeCurrencyUnits.java:31-34`), so the minor-unit maths is case-insensitive.

### 6.3 The cross-service amount contract with order-service

**The contract is: payment-service trusts the amount it is given. There is no amount agreement check anywhere.**

* payment-service **never calls order-service**. `grep -rn "order-service\|orderId" payment-service/src/main` shows `orderId` is only ever stored (`entity/Payment.java:23-24`) and echoed back. There is no client, no Feign/RestClient, no validation of the order.
* order-service calls payment-service in exactly two ways (`order-service/.../client/PaymentServiceClient.java`):
  * `:69-88` `findCapturedByOrderId(orderId)` → `GET /api/v1/payments/by-order/{orderId}/captured`; it only checks `STATUS_CAPTURED.equals(payment.status())` (`:80`). **The returned `amount` and `currency` are never compared against the order total.**
  * `:91-112` `refundByOrderId(orderId)` → `POST /api/v1/payments/{id}/refund`.
* Consequently: the amount in `payments.amount` is whatever the creator sent; the amount that the order was confirmed for is whatever order-service computed. **The two are never reconciled.** The confirm gate is a *status* gate, not an *amount* gate. `service/impls/PaymentServiceImpl.java:168-176` (`findCapturedByOrderId`) returns the captured payment but the caller checks only the status.
* **No production caller creates a payment.** `grep -rn "api/v1/payments" --include=*.java` across the repo (excluding `payment-service` and `.gitnexus`) finds only `PaymentServiceClient.java:72` and `:102` (read + refund) plus test stubs. `POST /api/v1/payments` and `POST /api/v1/payments/{id}/capture` (`controller/PaymentController.java:38-63`) have **no production caller** — only Postman collections (`docs/postman/*.json`) and tests. Since `PaymentController.java:43` restricts create to the `order-service` client id, no payment can be created or captured in the running system; the confirm gate therefore always fails closed (ORD-4012) and every order is auto-cancelled. PAY-20.

---

## 7. Idempotency at the DB level

### 7.1 The Stripe event id — YES, it is persisted under a UNIQUE constraint

**Changelog (cited):** `payment-service/src/main/resources/db/changelog/changelog-001-payments.yaml:72`

```yaml
        - sql:
            sql: CREATE UNIQUE INDEX uk_payment_events_provider_event ON payment_events (provider, provider_event_id)
```

**Column definitions:** `changelog-001-payments.yaml:55-57`

```yaml
              - column: { name: payment_id,        type: uuid,           constraints: { nullable: false } }
              - column: { name: provider,          type: varchar(16),    constraints: { nullable: false } }
              - column: { name: provider_event_id, type: varchar(128),   constraints: { nullable: false } }
```

**Repository method:** `repository/PaymentEventRepository.java:20`

```java
    Optional<PaymentEvent> findFirstByProviderAndProviderEventId(String provider, String providerEventId);
```

**Write path:** `service/WebhookEventService.java:116-126` (`writer.insertEvent(event)` inside a try/catch on `DataIntegrityViolationException`), and `service/PaymentWriter.java:53-56`:

```java
    @Transactional
    public PaymentEvent insertEvent(PaymentEvent event) {
        return eventRepository.saveAndFlush(event);
    }
```

For Stripe the persisted `provider_event_id` is the raw Stripe `evt_…` id — `service/WebhookEventService.java:236-275` builds the carrier payload with `.providerEventId(eventId)` where `eventId` is `event.getId()` (the Stripe event id), and the synthetic no-op path uses the same id (`service/WebhookEventService.java:277-287`). Asserted in `test/.../StripeWebhookIT.java:287-291` and `:326-328` ("still 1 event row").

**Verdict: the Stripe event id IS persisted under a DB-level UNIQUE constraint, and the constraint is the final arbiter of duplicate delivery.** Caveats: the uniqueness is on the **pair** `(provider, provider_event_id)`, and `provider` is the attacker-controlled path segment (PAY-04); the length is `varchar(128)` while a Stripe event id is ~30 chars — a malicious provider could collide across different bodies of the same id; and the constraint is `CREATE UNIQUE INDEX` (not a named `CONSTRAINT`), so it is invisible to entity-level validation.

### 7.2 The payment idempotency key — a GLOBAL unique index that contradicts the per-user code path

**Changelog (cited):** `changelog-001-payments.yaml:37`

```yaml
        - sql:
            sql: CREATE UNIQUE INDEX uk_payment_idempotency_key ON payments (idempotency_key)
```

**and** `changelog-006-payment-user-id.yaml:19-23` — the user-scoped index is **NOT unique**:

```yaml
        - createIndex:
            tableName: payments
            indexName: idx_payments_user_idempotency
            columns:
              - column: { name: user_id }
              - column: { name: idempotency_key }
```

**Repository method that assumes per-user scoping:** `repository/PaymentRepository.java:24`

```java
    Optional<Payment> findByIdempotencyKeyAndUserId(String idempotencyKey, UUID userId);
```

**Service code that assumes per-user scoping:** `service/impls/PaymentServiceImpl.java:60-62`

```java
        Optional<Payment> existing = (req.userId() != null)
                ? repository.findByIdempotencyKeyAndUserId(req.idempotencyKey(), req.userId())
                : repository.findByIdempotencyKey(req.idempotencyKey());
```

**The mismatch:** the lookup is user-scoped but the DB constraint is global. Two different users who pick the same idempotency key (e.g. `"order-123-pay-1"`) collide on `uk_payment_idempotency_key`. The insert fails with `DataIntegrityViolationException`, and the recovery path at `service/impls/PaymentServiceImpl.java:80-86` re-reads with the **unscoped** finder:

```java
        } catch (DataIntegrityViolationException ex) {
            // Lost the same-key race: the unique index is the final arbiter.
            // The raw constraint violation must never surface as a 500.
            Payment winner = repository.findByIdempotencyKey(req.idempotencyKey())
                    .orElseThrow(() -> ex);
            return replay(winner, req);
        }
```

`replay` then compares the payload (`service/impls/PaymentServiceImpl.java:100-117`) — the `userId` differs → `PAY-5003` (409). So user B is permanently blocked from using a key that user A used, and the 409 leaks the existence of another user's key. PAY-07.

Also note `userId` is never derived from the caller's identity: `controller/PaymentController.java:41-45` passes the DTO straight through, and `CreatePaymentRequest.userId` is an optional body field (`dto/CreatePaymentRequest.java:17`). The multi-tenant scoping is therefore entirely caller-asserted, and the only in-repo caller never sets it.

---

## 8. Security

### 8.1 Secrets handling — **PASS (no secret values in logs)**

* The Stripe secret key is read into a `@ConfigurationProperties` record (`config/PaymentStripeProperties.java:6-10`) and only ever used to build `RequestOptions` (`provider/StripeProvider.java:122`, `:129`). It is never logged.
* `provider/StripeProvider.java:41-43` logs the api-version only.
* `provider/StripeProvider.java:73-78` logs `paymentId`, `code`, `declineCode`, `requestId` — no card data, no key.
* `provider/StripeProvider.java:70` logs the **idempotency key** (`"Stripe replayed cached intent for idempotency key {}"`). That is a business identifier, not a secret, but it is correlatable with the order.
* `provider/MoMoProvider.java:46` logs `"Created MoMo transaction with signature for payment {}"` — it logs the *word* "signature", not the signature value. `provider/MoMoProvider.java:57` logs the refund ref. `provider/VNPayProvider.java:48` logs the payment id. **No secret value is written to any log in this module.**
* The webhook secret is injected with a fail-fast placeholder: `application.yml:51` `secret: ${PAYMENT_WEBHOOK_SECRET:?PAYMENT_WEBHOOK_SECRET must be set}` — the service refuses to boot without it.
* `provider/StripeProvider.java:31-34` and `config/PaymentProviderConfig.java:25-31` both fail fast on a blank Stripe key, naming the env var (`SHOP_PAYMENT_STRIPE_SECRET_KEY`) but not the value.

### 8.2 Client authorization on the webhook endpoint — **PUBLIC**

* `application.yml:65-67`:
  ```yaml
    public-paths:
      - method: POST
        path: /api/v1/webhooks/payments/**
  ```
* The gateway publishes the same path anonymously (`gateway-service/src/main/resources/application.yml:70` `- /api/v1/webhooks/payments/**`).
* `utils/common-security/.../BaseSecurityConfig.java:72-79` turns each `public-paths` rule into `permitAll()`.

**Abuse prevention is therefore *only* the HMAC.** There is:
* no per-provider secret — one `PAYMENT_WEBHOOK_SECRET` serves every non-Stripe provider (`application.yml:48-51`, `webhook/PaymentWebhookController.java:40`);
* no timestamp on the bare-hex branch ⇒ **no replay window** (`webhook/WebhookSignatureVerifier.java:112-120`); only the Stripe-style `t=` branch has the 300 s tolerance (`:160-164`);
* no body-size limit — `@RequestBody byte[] rawBody` buffers the whole body in memory on an anonymous path;
* no service-level rate limit (the gateway has one, `gateway-service/.../application.yml:87-99`, but the payment-service port is directly reachable inside the cluster);
* no provider-IP allow-list.

**The dedup key includes the attacker-chosen `{provider}` path segment** (`webhook/PaymentWebhookController.java:32`, `service/WebhookEventService.java:112`), so the same validly-signed body replayed to a *different* provider label is a **new** `(provider, provider_event_id)` pair, passes the unique index, and is processed a second time. With the bare-hex branch (no timestamp), a captured signed body can be replayed indefinitely against multiple provider labels. PAY-04, PAY-13, PAY-14.

### 8.3 Amount tampering risk

* The write endpoints are service-only: `controller/PaymentController.java:39` `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")` **plus** `controller/PaymentController.java:43` `serviceCallerGuard.requireCaller("order-service")` — the JWT `azp`/client id must be `order-service`. Asserted in `test/.../controller/PaymentControllerTest.java:353-362` (a `media-service` token → 403).
* `ROLE_USER` is rejected (`:122-139`), anonymous → 401 (`:99-117`).
* **The amount is fully caller-supplied and never reconciled** (§6.3). payment-service cannot detect a tampered amount because it never asks order-service what the order is worth; there is no cross-check, no ceiling, and no relationship to the order total. The only bounds are `@DecimalMin("0.01")` and `@Digits(integer = 17, fraction = 2)`.
* The webhook amount check is a **defence against provider-side tampering only**: `service/WebhookEventService.java:195-198` refuses a payload whose amount differs from the stored amount → `AMOUNT_MISMATCH` → the event is retried and then parked. It does **not** compare the currency (§6.2).
* The backoffice read API is ADMIN-only (`controller/BackofficePaymentController.java:24` `@PreAuthorize("hasRole('ADMIN')")`).

### 8.4 Can a user pay for another user's order?

**At the HTTP boundary, no — but the boundary is the wrong one, and the data model permits it.**

* There is **no user-facing payment endpoint**. Every write endpoint requires `ROLE_SERVICE`/`ROLE_ADMIN` **and** the `order-service` client id (`controller/PaymentController.java:39,43,48,52,57,61`). A customer token (`ROLE_USER`) gets 403. So a user cannot create, capture, or refund a payment directly.
* The `userId` on the payment row (`entity/Payment.java:48-50`) is **not** derived from the caller's principal; it is a body field (`dto/CreatePaymentRequest.java:17`) asserted by the caller. `userId` is also nullable, and `controller/PaymentController.java:41-44` never populates it.
* Consequence: the only "user isolation" in the payment domain is the idempotency-key scoping of §7.2, which (a) is enforced by a **global** unique index, not by a user-scoped one, and (b) is inert because no production caller sends a `userId`. `test/.../repository/PaymentRepositoryIdempotencyScopingTest.java` only mocks the repository — it never exercises the DB constraint.
* **However**: a webhook for a payment belonging to another user is fully accepted. The webhook path resolves the payment purely by `payload.paymentId()` (`service/WebhookEventService.java:184-186`) with **no user/tenant check** — the provider is trusted to name the right payment. If a provider is compromised, or the shared webhook secret leaks, an attacker can drive any payment's state.
* The order-service confirm gate (`PaymentServiceClient.java:69-88`) looks up by `orderId` only — no user dimension. So "who owns this payment" is never checked anywhere in the payment domain.

---

## 9. Design pattern evaluation

| Pattern | Present? | Evidence | Verdict |
|---|---|---|---|
| **Strategy** | **Yes** | `provider/PaymentProvider.java:6-16` is the strategy interface; `MockProvider`, `CodProvider`, `MoMoProvider`, `VNPayProvider`, `StripeProvider` are the strategies; selection is per-request via `PaymentServiceImpl.resolveProvider` (`service/impls/PaymentServiceImpl.java:50-55`) and `PaymentProviderFactory.getProvider` (`provider/PaymentProviderFactory.java:34-43`). | **Genuinely present.** Two defects: the strategy interface is too narrow (§2.6) and the registry falls back instead of failing closed (PAY-02). |
| **Factory / Registry** | **Yes** | `provider/PaymentProviderFactory.java:15-59` — a `ConcurrentHashMap` registry built from the Spring-injected `List<PaymentProvider>` (`:20-28`), keyed by upper-cased `name()`. Spring wiring at `provider/PaymentProviderConfig.java:49-53`. | **Genuinely present and correctly wired.** Weaknesses: silent fallback (`:40-42`), arbitrary `values().iterator().next()` default (`:51`), duplicate `name()` values silently overwrite (`:25`). |
| **Adapter** | **Yes** | `provider/StripeProvider.java` adapts the Stripe SDK (`PaymentIntent`, `Refund`, `PaymentIntentSearchParams`) to the port; error mapping at `:67-81`; minor-unit conversion delegated to `provider/StripeCurrencyUnits.java`. | **Genuinely present.** The adapter is *lossy*: it cannot express "unknown outcome", it discards `client_secret`, and it maps a transport timeout onto "rejected" (PAY-09). |
| **State** | **Partial** | `service/PaymentStateMachine.java:10-33` is a table-driven transition validator, but it is a *static utility*, not a per-state behaviour object. It is consulted **only** by the webhook path (`service/WebhookEventService.java:199`, `:226`); the REST path bypasses it entirely (`service/impls/PaymentServiceImpl.java:132-133`, `:152-153`). | **Half-present.** A real State pattern would encapsulate the allowed transitions *with* the side effect (publish the outbox event, update `refunded_amount`) so the two paths cannot diverge. Recommend `PaymentState` interface + per-status implementations, or at minimum route every mutation through `PaymentStateMachine.transition` + a single `PaymentTransitionHandler`. |
| **Saga participant** | **No** | payment-service has **no Kafka consumer** (`grep -rn "@KafkaListener" payment-service/src/main` → 0 hits) and no compensation logic. It publishes `payment.captured.v1` / `payment.refunded.v1` / `payment.failed.v1` / `payment.partially_refunded.v1` (`service/WebhookEventService.java:378-386`, `outbox/PaymentEventPublisher.java:28-37`) but never reacts to `order.cancelled` / `order.confirmed`. | **Absent.** This is the root cause of PAY-06. A payment service that charges but cannot compensate for a cancelled order is half a Saga. Recommend a consumer for `shop.order.lifecycle.v1` that transitions `CAPTURED → REFUNDED` on `order.cancelled` (or a `PaymentCompensationService` invoked by the orchestrator). |
| **Idempotent Consumer** | **Partial** | The webhook consumer *is* idempotent by construction: `(provider, provider_event_id)` UNIQUE (`changelog-001-payments.yaml:72`), PROCESSED-status short-circuit (`service/WebhookEventService.java:113-115`), `DataIntegrityViolationException` catch (`:122-125`), state-machine guard (`:199`). | **Partial.** The *store* is idempotent, but the *effect* is not: the state machine allows self-transitions (`service/PaymentStateMachine.java:26-28`) so a re-processed event publishes a second lifecycle event (PAY-03), and the retry scheduler has no claim (PAY-05). |
| **Template Method** | **No (explicitly rejected in favour of composition)** | The outbox relay is a three-phase pipeline with the transaction boundary supplied by a `TransactionTemplate` (`outbox/PaymentOutboxRelay.java:97-103`), and the webhook service delegates to a `PaymentWriter` collaborator (`service/PaymentWriter.java:15-82`). There is no abstract base class with overridable steps. | **Not present, and not needed.** The composition used (`PaymentWriter` as a collaborator, `TransactionTemplate` for the tx boundary) is the better fit here; a Template Method base class would add an inheritance axis with no benefit. **No action.** |
| **Outbox (bonus)** | **Yes, and well built** | `outbox/PaymentEventPublisher.java:28-62` writes the row in the caller's transaction; `outbox/PaymentOutboxRelay.java:105-163` claims → publishes → marks; `outbox/OutboxEventRepository.java:35-37` uses `PESSIMISTIC_WRITE` + `lock.timeout = -2` (`FOR UPDATE SKIP LOCKED`); `outbox/OutboxEventRepository.java:79-90` reclaims stranded `SENDING` rows; `outbox/PaymentOutboxRelay.java:252-255` preserves publish order by id. Covered by `test/.../outbox/PaymentOutboxClaimConcurrencyIT.java`, `PaymentOutboxRelayRecoveryIT.java`, `PaymentOutboxBatchAsyncIT.java`, `PaymentOutboxRelayOrderTest.java`. | **Genuinely present and the strongest pattern in the module.** Two gaps: the outbox row has no uniqueness on `(aggregate_id, event_type)` so a duplicate lifecycle event is storable (PAY-03), and `FAILED` rows are never purged (PAY-18). |

---

## 10. Defect list

Severity scale: **CRITICAL** = money moved/not moved incorrectly or a financial invariant broken in production; **HIGH** = a reachable production race/authorisation gap with financial consequence; **MEDIUM** = correctness/robustness defect with a plausible production trigger; **LOW** = hygiene, dead code, misleading telemetry.

---

### PAY-01 — CRITICAL — A capture marks the payment CAPTURED while no money has moved, and the Stripe intent can never be confirmed

**Evidence**
* `provider/StripeProvider.java:54-63` — the PaymentIntent is created with `.setConfirm(false)` and no payment method:
  ```java
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(amount, currency))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setConfirm(false)
  ```
* `service/impls/PaymentServiceImpl.java:127-135` — the local row is flipped to `CAPTURED` the moment the intent is *created*:
  ```java
        ProviderResult result =
                provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
        ...
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = writer.save(payment);
        writer.publishLifecycleEvent(saved, "payment.captured.v1");
  ```
* `dto/PaymentResponse.java:10-20` — `PaymentResponse` has **no** `clientSecret` field, and `provider/PaymentProvider.java:14-15` — `ProviderResult` has no field for it either. `grep -rn "clientSecret" payment-service/src/main` → no hits.
* `provider/StripeProvider.java:110-115` — the intent id is never persisted either ("no provider-reference column").

**Actual behaviour:** the intent is left in `requires_confirmation`; its `client_secret` is discarded; no code path confirms it; therefore `payment_intent.succeeded` never fires. Meanwhile the local payment is `CAPTURED`, `payment.captured.v1` is published to Kafka, and the order-service confirm gate opens — goods are shipped for a payment that was never charged. The StripeWebhookIT only proves the webhook path works *when a webhook arrives* (`test/.../StripeWebhookIT.java:276-292`), which in production it never will.

**Violated invariant:** `PaymentStatus.CAPTURED` must mean "funds captured at the PSP".

**Root cause:** the port (`PaymentProvider`) models "capture" as a single synchronous boolean and cannot express a two-phase (create → confirm → settle) flow.

**Proposed fix:** either (a) confirm server-side — create the intent with `confirm=true` + a payment method and treat a `requires_action` result as `PENDING` with the `client_secret` returned to the client, or (b) make `PaymentProvider.capture` return a richer result (`PaymentProviderIntent` carrying the provider reference + client secret + a `requiresAction` flag), persist the provider reference on the `payments` row, and only set `CAPTURED` when the provider confirms.

**Reproduction test:** `payment-service/src/test/java/com/shop/paymentservice/provider/StripeProviderTest.java` — a new test that asserts `provider.capture(...)` does **not** result in `PaymentStatus.CAPTURED` when the intent status is `requires_confirmation`/`requires_action` (currently the SDK returns `requires_action` in `test/.../provider/StripeProviderTest.java:75-91` and the adapter still reports `accepted=true`).

**Acceptance criterion:** with `shop.payment.provider=stripe`, a capture of a card that requires action leaves the payment `PENDING` and the response carries a client secret (or a provider reference the client can confirm); the payment reaches `CAPTURED` only after a confirmed provider event.

---

### PAY-02 — CRITICAL — An unknown provider name silently falls back to the default provider, so a refund can "succeed" against the wrong PSP

**Evidence**
* `provider/PaymentProviderFactory.java:34-43`:
  ```java
        PaymentProvider p = providers.get(providerName.trim().toUpperCase(Locale.ROOT));
        if (p == null) {
            return getDefaultProvider();
        }
  ```
* `provider/PaymentProviderFactory.java:45-55` — the default itself falls back to an arbitrary map entry when the configured default is absent.
* `service/impls/PaymentServiceImpl.java:146-154`:
  ```java
        PaymentProvider provider = resolveProvider(payment.getProvider());
        ProviderResult result =
                provider.refund(payment.getId(), payment.getAmount(), payment.getIdempotencyKey());
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
        payment.setPreviousStatus(PaymentStatus.CAPTURED);
        payment.setStatus(PaymentStatus.REFUNDED);
  ```

**Actual behaviour:** a payment row captured by `stripe` (or `VNPAY`) is refunded through whatever provider happens to be registered. If the deployment switched `shop.payment.provider` from `stripe` to `mock` (or the Stripe bean is not loaded), `resolveProvider("stripe")` returns `MockProvider`, `MockProvider.refund` returns `accepted=true` (`provider/MockProvider.java:23-25`), and the row is set to `REFUNDED` — **the customer never gets their money back and the system says they did.** The same substitution happens on the create path when `req.provider()` is a typo (`service/impls/PaymentServiceImpl.java:67`) — a request intended for COD is captured via the default PSP.

**Violated invariant:** a refund must be executed by the same PSP that captured, and a provider that cannot handle the request must fail closed.

**Root cause:** the registry models "unknown → default" instead of "unknown → error".

**Proposed fix:** `PaymentProviderFactory.getProvider` must throw `BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, "unknown provider: " + providerName)` when the name is unknown and non-blank; `getDefaultProvider` must throw when the configured default is not registered (instead of `values().iterator().next()`); `PaymentServiceImpl.capture/refund` should compare `provider.name()` with `payment.getProvider()` and refuse a mismatch.

**Reproduction test:** `test/.../provider/PaymentProviderConfigTest.java` — a test that builds a factory with only `MockProvider` and asserts `factory.getProvider("stripe")` throws instead of returning mock.

**Acceptance criterion:** `getProvider(<unregistered>)` throws `PAY-5008`; a refund of a `provider='stripe'` payment never reaches a non-Stripe adapter; `PaymentProviderConfig.primary` never silently selects an arbitrary provider.

---

### PAY-03 — HIGH — The state machine allows self-transitions, so a re-processed webhook publishes a second `payment.captured.v1`

**Evidence**
* `service/PaymentStateMachine.java:26-28`:
  ```java
        if (from == to) {
            return to;
        }
  ```
* `service/WebhookEventService.java:199-211`:
  ```java
        PaymentStatus next = PaymentStateMachine.transition(payment.getStatus(), target);
        payment.setPreviousStatus(payment.getStatus());
        payment.setStatus(next);
        ...
        Payment saved = writer.completeWithEvent(payment, event, outboxEventType(next));
  ```
* `service/PaymentWriter.java:74-81` — `completeWithEvent` **always** publishes:
  ```java
    public Payment completeWithEvent(Payment payment, PaymentEvent event, String outboxEventType) {
        event.setStatus(PaymentEvent.STATUS_PROCESSED);
        eventRepository.saveAndFlush(event);
        Payment saved = repository.saveAndFlush(payment);
        publisher.publish(saved, outboxEventType);
        return saved;
    }
  ```
* `outbox/PaymentEventPublisher.java:39-62` — every publish writes a **new** row with a fresh `UUID.randomUUID()` event id; `changelog-002-payment-outbox.yaml` has no uniqueness on `(aggregate_id, event_type)`.

**Actual behaviour:** the REST capture path (`service/impls/PaymentServiceImpl.java:132-135`) sets `CAPTURED` and publishes `payment.captured.v1`. The Stripe `payment_intent.succeeded` webhook that follows finds the payment already `CAPTURED`; `transition(CAPTURED, CAPTURED)` is legal → the webhook publishes a **second** `payment.captured.v1`. The same duplication occurs on every retry of a webhook whose processing failed *after* `completeWithEvent` (e.g. `attachReceipt` throwing at `service/WebhookEventService.java:212-214` → `markFailedRetryable` at `:173-174` → the event is replayed → published again). The outbox row count, the Kafka record count and the receipt write all double.

**Violated invariant:** exactly one `payment.<status>.v1` event per (payment, status) transition.

**Root cause:** "idempotent" was implemented as *state* idempotency (the final state is the same) instead of *effect* idempotency (the side effect must not repeat).

**Proposed fix:** make the publish conditional on a real transition — in `completeWithEvent`, skip `publisher.publish` when `payment.getStatus() == payment.getPreviousStatus()`; and/or add `CREATE UNIQUE INDEX uk_outbox_aggregate_event ON outbox_events (aggregate_id, event_type)` and make the publisher tolerate the duplicate. A cleaner shape is to have `PaymentStateMachine` return a `Transition(from, to, boolean changed)` and let the caller publish only when `changed`.

**Reproduction test:** `test/.../service/WebhookEventServiceTest.java` — add a case: payment already `CAPTURED`, webhook `CAPTURED` arrives → assert `publisher.publish` is **not** called a second time (the current test `idempotentTransition_capturedToCaptured_completesWithEvent` at `:258-267` asserts the opposite — it must be rewritten).

**Acceptance criterion:** after a REST capture *and* the corresponding webhook, exactly one `payment.captured.v1` exists in `outbox_events` and exactly one Kafka record is observed for that payment.

---

### PAY-04 — HIGH — The webhook dedup key contains the attacker-controlled `{provider}` path segment, so one signed body can be replayed across provider labels

**Evidence**
* `webhook/PaymentWebhookController.java:30-44` — `provider` is the raw path variable and is passed straight into the handler.
* `service/WebhookEventService.java:105-132` — dedup is `(provider, providerEventId)`:
  ```java
        Optional<PaymentEvent> existing = eventRepository
                .findFirstByProviderAndProviderEventId(provider, providerEventId);
  ```
* `changelog-001-payments.yaml:72` — the UNIQUE index is on the same pair:
  ```yaml
              sql: CREATE UNIQUE INDEX uk_payment_events_provider_event ON payment_events (provider, provider_event_id)
  ```
* `application.yml:48-51` — one secret for every non-Stripe provider.

**Actual behaviour:** an attacker who observes one validly-signed non-Stripe webhook body (or who possesses the shared secret) POSTs it to `/api/v1/webhooks/payments/momo` and `/api/v1/webhooks/payments/vnpay`. Each request produces a *different* `(provider, provider_event_id)` pair, so the unique index does not fire, and the payment is driven through the transition a second time. Combined with the self-transition allowance (PAY-03) this yields a duplicate `payment.captured.v1` and a second receipt write. The bare-hex signature branch has no timestamp, so this replay never expires (`webhook/WebhookSignatureVerifier.java:112-120`).

**Violated invariant:** a provider event id identifies one provider event globally; a signed body must be processable at most once.

**Root cause:** the provider label is transport metadata, but it was made part of the dedup identity while the signature is provider-independent.

**Proposed fix:** key the dedup on `provider_event_id` alone (with a `UNIQUE (provider_event_id)` index), or map the path segment to a *registered* provider and use the registered provider's own secret for verification so a body signed for provider A cannot be replayed to provider B. Also reject an unregistered `{provider}` with 404/401 instead of accepting it.

**Reproduction test:** `test/.../webhook/PaymentWebhookControllerTest.java` — post the same signed body to two different provider paths and assert exactly one `payment_events` row and one outbox row result.

**Acceptance criterion:** the same signed body submitted to N different provider paths produces exactly one `payment_events` row and at most one lifecycle event.

---

### PAY-05 — HIGH — The webhook retry scheduler has no claim/lease: two replicas replay the same event concurrently

**Evidence**
* `scheduler/WebhookRetryScheduler.java:61-74`:
  ```java
    @Scheduled(fixedDelayString = "${shop.payment.webhook.retry-poll-ms:60000}")
    public void replay() {
        Instant now = Instant.now();
        List<PaymentEvent> batch = eventRepository
                .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                    PaymentEvent.STATUS_FAILED_RETRYABLE, now, PageRequest.of(0, batchSize));
  ```
* `repository/PaymentEventRepository.java:26-27` — a plain derived query, no `@Lock`, no `SKIP LOCKED`, no claim column.
* Contrast with the outbox relay, which does it right: `outbox/OutboxEventRepository.java:35-37` `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))`.

**Actual behaviour:** with ≥2 payment-service replicas, both schedulers select the same `FAILED_RETRYABLE` rows every 60 s and both call `WebhookEventService.retry(event)` → `process(payload, event)` (`service/WebhookEventService.java:141-155`, `:183-215`). Both attempt the state transition; the optimistic lock (`entity/Payment.java:59-62`) lets only one commit, and the loser flips the event back to `FAILED_RETRYABLE` (`markFailedRetryable` `:289-293`) → the retry loop continues → the event is eventually processed again, publishing a **second** lifecycle event (PAY-03). The retry budget (`MAX_ATTEMPTS = 6`, `scheduler/WebhookRetryScheduler.java:40`) is consumed by the duplicate work, so a genuinely transient failure can be parked as `FAILED_PERMANENT` without 6 real attempts.

**Violated invariant:** a retry queue item is owned by exactly one worker at a time.

**Root cause:** the outbox relay pattern (`claim → publish → mark`, with `SKIP LOCKED`) was applied to the outbox but not to the webhook retry queue.

**Proposed fix:** add `SELECT … FOR UPDATE SKIP LOCKED` (a `@Lock(PESSIMISTIC_WRITE)` + `lock.timeout = -2` query, or a `claimed_at`/`claimed_by` lease column with a `UPDATE … RETURNING` claim), and mark the event as claimed before processing. Mirror the outbox's three-phase design.

**Reproduction test:** a new IT (`test/.../scheduler/…`) that starts two `WebhookRetryScheduler` instances against one Testcontainers Postgres, seeds one `FAILED_RETRYABLE` event, and asserts exactly one `completeWithEvent` and exactly one outbox row.

**Acceptance criterion:** with two scheduler instances running concurrently, each `FAILED_RETRYABLE` event is processed exactly once and produces exactly one lifecycle event.

---

### PAY-06 — HIGH — No Saga compensation: a webhook that arrives after the order was cancelled captures the payment with no refund

**Evidence**
* `service/WebhookEventService.java:183-215` — `process()` never consults the order; `PENDING → CAPTURED` is legal (`service/PaymentStateMachine.java:13`).
* `grep -rn "@KafkaListener" payment-service/src/main` → **no hits**: payment-service consumes nothing, so it never learns that the order was cancelled.
* The order-service confirm gate fails closed by design: `order-service/.../client/PaymentServiceClient.java:84-87` → `Optional.empty()` → `ORD-4012` (`utils/common-core/.../ErrorCode.java:118`).

**Actual behaviour:** the customer pays, the order is cancelled (timeout / user cancel / fraud review), then Stripe's `payment_intent.succeeded` arrives. The payment becomes `CAPTURED`, `payment.captured.v1` is published, the order-service tries to confirm a cancelled order and fails closed, and the order is auto-cancelled. **The money is captured and never returned.** There is no code path in payment-service that can issue a compensating refund for a cancelled order.

**Violated invariant:** a captured payment must correspond to a live order, or the difference must be compensated.

**Root cause:** payment-service is a participant in the order→payment saga but has no compensation participant role and no inbound order-lifecycle consumer.

**Proposed fix:** make payment-service a Saga participant — consume `shop.order.lifecycle.v1` and, on `order.cancelled` / `order.expired` for an order with a `CAPTURED` payment, drive `CAPTURED → REFUNDED` through the same state machine (publishing `payment.refunded.v1`). Alternatively add a `PaymentCompensationService` invoked by the orchestrator.

**Reproduction test:** an IT that publishes `order.cancelled` for an order with a captured payment and asserts the payment ends `REFUNDED` with a `payment.refunded.v1` outbox row.

**Acceptance criterion:** every captured payment whose order is cancelled is refunded within a bounded time, with a metric for the compensation.

---

### PAY-07 — HIGH — The DB idempotency-key uniqueness is global while the code assumes per-user scoping

**Evidence**
* `changelog-001-payments.yaml:37` — `CREATE UNIQUE INDEX uk_payment_idempotency_key ON payments (idempotency_key)` — **global**.
* `changelog-006-payment-user-id.yaml:19-23` — `idx_payments_user_idempotency` on `(user_id, idempotency_key)` — **not unique**.
* `repository/PaymentRepository.java:24` — `findByIdempotencyKeyAndUserId` (scoped).
* `service/impls/PaymentServiceImpl.java:60-62` (scoped lookup) and `:83-85` (**unscoped** fallback after the constraint fires):
  ```java
            Payment winner = repository.findByIdempotencyKey(req.idempotencyKey())
                    .orElseThrow(() -> ex);
            return replay(winner, req);
  ```
* `service/impls/PaymentServiceImpl.java:110-117` — `samePayload` compares `userId`, so a cross-user collision becomes a `PAY-5003` conflict.

**Actual behaviour:** user B submits an idempotency key that user A already used (keys are derived from order ids and are therefore *predictable*). B's scoped lookup misses, the insert violates the **global** index, the unscoped re-read finds **A's payment**, `samePayload` fails on `userId`, and B receives `PAY-5003` — permanently blocked, and told that the key is taken. B can never use that key, and the 409 is a cross-tenant key-existence oracle.

**Violated invariant:** an idempotency key must be scoped to the caller; one tenant must not be able to block or probe another tenant's keys.

**Root cause:** the schema was never migrated to match the H29 per-user scoping the repository was written for.

**Proposed fix:** `changelog-009`: `DROP INDEX uk_payment_idempotency_key;` then `CREATE UNIQUE INDEX uk_payments_user_idem ON payments (user_id, idempotency_key) WHERE user_id IS NOT NULL;` plus `CREATE UNIQUE INDEX uk_payments_idem_legacy ON payments (idempotency_key) WHERE user_id IS NULL;`. Also change the fallback re-read at `:83` to the scoped finder and, when it misses, rethrow the original constraint violation.

**Reproduction test:** an IT that creates a payment for user A with key K and then creates one for user B with key K → expect two rows, no `PAY-5003`.

**Acceptance criterion:** two users can use the same idempotency key simultaneously; one user cannot infer or block another user's key.

---

### PAY-08 — HIGH — The Stripe intent id is never persisted, so a refund must use the eventually-consistent Search API and usually fails

**Evidence**
* `provider/StripeProvider.java:110-125`:
  ```java
    /**
     * The port hands us only the LOCAL payment id — the Stripe intent id was
     * never persisted (no provider-reference column). The intent was created
     * with {@code metadata[payment_id]} at capture time, so the deterministic
     * way back is the Stripe Search API (no 24h idempotency-cache race).
     */
    private PaymentIntent findIntentByLocalPaymentId(UUID paymentId) throws StripeException {
        PaymentIntentSearchParams searchParams = PaymentIntentSearchParams.builder()
                .setQuery("metadata['payment_id']:'" + paymentId + "'")
  ```
* `provider/StripeProvider.java:87-90`:
  ```java
            PaymentIntent original = findIntentByLocalPaymentId(paymentId);
            if (original == null) {
                return new ProviderResult("stripe-error-intent-not-found", false);
            }
  ```
* `entity/Payment.java` — there is no `provider_reference` column (grep of the entity shows only `order_id`, `amount`, `currency`, `status`, `previous_status`, `provider`, `idempotency_key`, `user_id`, `receipt_key`, `refunded_amount`, `version`).

**Actual behaviour:** Stripe's Search API is eventually consistent (indexing lags by seconds to minutes). A refund requested immediately after the capture finds no intent → `accepted=false` → `PAY-5008` → **no money is returned**, and the caller (order-service `refundByOrderId`, `order-service/.../PaymentServiceClient.java:91-112`) treats it as a failed refund and returns `false`. There is no retry of the refund and no reconciliation job.

**Violated invariant:** a refund of a captured payment must be executable deterministically.

**Root cause:** the port returns only a boolean, so the provider reference is discarded at the boundary.

**Proposed fix:** add a `provider_reference` column (`varchar(128)`) to `payments`, persist `ProviderResult.providerEventId()` on capture (`service/impls/PaymentServiceImpl.java:127-134`), and refund by that id. Fall back to the Search API only for rows written before the migration.

**Reproduction test:** a `StripeProvider` unit test asserting `refund()` calls `Refund.create` with the persisted reference and **never** calls `PaymentIntent.search`.

**Acceptance criterion:** a refund of a payment captured 2 seconds earlier succeeds; the Stripe Search API is not on the refund path.

---

### PAY-09 — HIGH — Provider failure mapping conflates "declined" with "unknown outcome"; a Stripe timeout is reported as a definitive rejection

**Evidence**
* `provider/StripeProvider.java:76-81`:
  ```java
        } catch (StripeException e) {
            log.error("Stripe API error on capture (paymentId={}, code={}, requestId={})",
                    paymentId, e.getCode(), e.getRequestId(), e);
            return new ProviderResult("stripe-error-"
                    + (e.getCode() == null ? e.getClass().getSimpleName() : e.getCode()), false);
        }
  ```
  `ApiConnectionException` (the SDK's timeout) is a `StripeException` → `accepted=false`. Asserted in `test/.../provider/StripeProviderTest.java:157-168` (`captureStripeInfrastructureError_mapsToRejected`).
* `service/impls/PaymentServiceImpl.java:129-131`:
  ```java
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
  ```
* `provider/StripeProvider.java:67-71` — the idempotency-conflict branch returns a **fabricated** provider event id `"idempotent-" + idempotencyKey` with `accepted=true`.

**Actual behaviour:** on a network timeout the caller receives `502 PAY-5008` ("provider rejected"), but the PaymentIntent may have been created at Stripe. The local row stays `PENDING`; the caller's retry (same key) is replayed by Stripe within 24 h — good — but after 24 h the same key creates a *second* intent. The `IdempotencyException` branch is worse: it reports success with an event id that does not exist at Stripe, so the local `CAPTURED` state has no provider-side counterpart at all.

**Violated invariant:** a transport failure must not be reported as a merchant/payment failure; the outcome of an ambiguous provider call must be recorded and reconciled.

**Root cause:** the binary `ProviderResult.accepted` flag has no "unknown" state, and the provider call is not preceded by a persisted intent record.

**Proposed fix:** widen the port to a tri-state (`ACCEPTED / REJECTED / UNKNOWN`) or a result object carrying a failure category; on `UNKNOWN` persist the payment in a new `PENDING_PROVIDER`/`RECONCILE` state, return `202`/503 (not 502), and reconcile asynchronously by searching the PSP for the idempotency key. Replace the fabricated `"idempotent-"` id with the real intent id obtained from the idempotency-replay lookup.

**Reproduction test:** `test/.../provider/StripeProviderTest.java` — a case asserting that an `ApiConnectionException` yields an `UNKNOWN` outcome (not `REJECTED`), and that the service maps it to a non-502 status.

**Acceptance criterion:** a provider timeout never produces `PAY-5008`; the payment row is left in a reconcilable state and a reconciliation job can resolve it without operator action.

---

### PAY-10 — HIGH — The PSP call happens inside the database transaction, with no client timeout

**Evidence**
* `service/impls/PaymentServiceImpl.java:119-137` — `@Transactional` on `capture`, and the provider call at `:127-128` is inside it. Same for `refund` (`:139-157`, call at `:147-148`).
* `grep -rn "timeout" payment-service/src/main` → the only timeout property is the outbox publish timeout (`outbox/PaymentOutboxRelay.java:83`). **No Stripe connect/read timeout is configured anywhere**, so the stripe-java 24.24.0 default (80 s) applies.

**Actual behaviour:** a slow or hung Stripe endpoint holds a pooled PostgreSQL connection *and* an open transaction for up to ~80 s per request. Under modest concurrency the Hikari pool is exhausted; every other payment operation (including the read-only confirm-gate lookup) then blocks behind it. The transaction also holds a row version check open, increasing the chance of the optimistic-lock failures of PAY-12.

**Violated invariant:** an external network call must not be made while holding a database transaction and a pooled connection.

**Root cause:** the provider call was placed inside the transactional service method for convenience.

**Proposed fix:** configure explicit Stripe timeouts (`RequestOptions` has no timeout field — set `Stripe.setConnectTimeout`/`setReadTimeout`, or use a `HttpClient` with a bounded timeout), and restructure `capture`/`refund` into (1) a short transaction that records the intent (status `PENDING` + `provider_reference`), (2) the provider call outside any transaction, (3) a short transaction that records the outcome (compare-and-set on `status`/`version`).

**Reproduction test:** an IT that stubs the PSP with a 60-second delay and asserts that the DB pool is not exhausted and that the transaction duration stays bounded.

**Acceptance criterion:** no PSP call is issued inside a `@Transactional` method; a slow PSP cannot exhaust the connection pool; the configured PSP timeout is asserted in a unit test.

---

### PAY-11 — MEDIUM — The REST capture/refund path bypasses the state machine and never updates `refunded_amount`

**Evidence**
* `service/impls/PaymentServiceImpl.java:132-133` and `:152-153` set the status directly; `PaymentStateMachine` is referenced **only** at `service/WebhookEventService.java:199` and `:226` (grep confirmed).
* `service/impls/PaymentServiceImpl.java:141-157` — the refund path never calls `setRefundedAmount` (grep `setRefundedAmount` → only `WebhookEventService.java:205` and `:230`).
* `service/WebhookEventService.java:217-225` — the partial-refund arithmetic depends on `refundedAmount`:
  ```java
        BigDecimal already = payment.getRefundedAmount() == null
                ? BigDecimal.ZERO : payment.getRefundedAmount();
        BigDecimal remaining = payment.getAmount() == null
                ? BigDecimal.ZERO : payment.getAmount().subtract(already);
  ```

**Actual behaviour:** after a REST full refund the row is `REFUNDED` with `refunded_amount = 0`. A later partial-refund webhook computes `remaining = amount - 0 = amount`, accepts the payload, and then the state machine rejects `REFUNDED → PARTIALLY_REFUNDED` as illegal → the event is marked `FAILED_RETRYABLE` and retried 6 times before being parked as `FAILED_PERMANENT`, even though the money *was* partially refunded at Stripe. The audit trail (`refunded_amount`) is wrong for every REST-refunded payment.

**Violated invariant:** `refunded_amount` must always equal the total refunded at the PSP; every status change must pass the same transition validator.

**Root cause:** two independent implementations of the same transition rules, in different classes, with different side effects.

**Proposed fix:** route both paths through a single `PaymentTransitionService` that calls `PaymentStateMachine.transition`, maintains `refunded_amount`, and publishes the lifecycle event exactly once (this also fixes PAY-03). Add `PARTIALLY_REFUNDED` support to the REST API or explicitly reject a partial refund there.

**Reproduction test:** `test/.../service/impls/PaymentServiceImplTest.java` — a case asserting that `refund()` sets `refundedAmount == amount` and that the transition is validated by `PaymentStateMachine`.

**Acceptance criterion:** `refunded_amount` is correct after every refund path; no code path can set a status without passing `PaymentStateMachine.transition`.

---

### PAY-12 — MEDIUM — Optimistic-lock failures and provider side effects are not coordinated; the caller gets a 500

**Evidence**
* `entity/Payment.java:59-62`:
  ```java
      @Version
      @Column(name = "version")
      @Builder.Default
      private Long version = 0L;
  ```
* `service/impls/PaymentServiceImpl.java:134` `Payment saved = writer.save(payment);` — no catch for `ObjectOptimisticLockingFailureException`.
* `service/impls/PaymentServiceImpl.java:147-148` — the provider refund is executed *before* the write, so the side effect happens even if the write fails.

**Actual behaviour:** two concurrent captures (a duplicate `POST /capture`, or a capture racing a webhook) → one commits, the other throws → the REST caller receives **HTTP 500** (not 409). Worse for refunds: the Stripe refund has already been executed at the PSP when the version check fails, so the caller retries a refund that may already have succeeded (mitigated only by the Stripe idempotency key, and only for the same amount, `provider/StripeProvider.java:92`).

**Violated invariant:** a concurrent mutation must produce a deterministic 409/200, never a 500, and must not execute an irreversible external side effect before the state check succeeds.

**Root cause:** the optimistic lock is left to surface as an unhandled infrastructure exception, and the external call precedes the state transition.

**Proposed fix:** catch `ObjectOptimisticLockingFailureException` and map it to `PAY-5004` (409) with a re-read; perform the provider call *after* a compare-and-set on the status (claim `PENDING → CAPTURING` inside the transaction, then call the PSP, then commit `CAPTURING → CAPTURED`).

**Reproduction test:** a concurrency IT that fires two `capture()` calls at one payment and asserts one 200 and one 409 (never a 500).

**Acceptance criterion:** no payment endpoint returns 5xx for a concurrent duplicate; the PSP is called at most once per capture attempt.

---

### PAY-13 — MEDIUM — The bare-hex signature branch has no timestamp and no nonce, so signed webhooks can be replayed forever

**Evidence**
* `webhook/WebhookSignatureVerifier.java:112-120`:
  ```java
        if (signatureHeader.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        try {
            byte[] provided = HexFormat.of().parseHex(signatureHeader);
            return constantTimeEquals(provided, hmacBytes(secret, rawBody));
        } catch (RuntimeException e) {
            return false;
        }
  ```
* `webhook/WebhookSignatureVerifier.java:46` `DEFAULT_TOLERANCE_SECONDS = 300L` applies only to the Stripe-style branch (`:160-164`).
* `webhook/PaymentWebhookController.java:40` — the non-Stripe path calls the 3-arg overload `verify(secret, rawBody, signatureHeader)`, which is the branch with **no** timestamp handling.
* The production usage of this branch is confirmed by `test/.../PaymentFlowIT.java:305-313` (bare hex HMAC over the body) and `test/.../webhook/PaymentWebhookControllerTest.java:44-52`.

**Actual behaviour:** a captured signed body (from a proxy log, a misconfigured mirror, or an insider) can be replayed at any time in the future. The only defence is the `(provider, provider_event_id)` unique index — which PAY-04 shows is bypassable by changing the path segment.

**Violated invariant:** a webhook must be accepted only within a bounded time of its emission.

**Root cause:** the legacy bare-hex shape was retained for compatibility and silently became the *only* shape the non-Stripe providers use, inheriting no replay protection.

**Proposed fix:** require the timestamped `t=…,v1=…` shape for every provider (the verifier already supports it — `webhook/WebhookSignatureVerifier.java:88-110`); keep the bare-hex shape behind an explicit, off-by-default property and only for local development. Optionally add a nonce and a `seen_nonces` table.

**Reproduction test:** `test/.../webhook/WebhookSignatureVerifierTest.java` — assert that a bare-hex header older than the tolerance is rejected (today it is accepted).

**Acceptance criterion:** no provider path accepts a signature without a timestamp; a replay after the tolerance window is rejected with `PAY-5005`.

---

### PAY-14 — MEDIUM — One webhook secret is shared by all non-Stripe providers, so a payload signed for one provider is valid on another

**Evidence**
* `application.yml:48-51` — a single `shop.payment.webhook.secret`.
* `webhook/PaymentWebhookController.java:25,40` — the same `webhookSecret` is used for every non-Stripe provider path.

**Actual behaviour:** a compromise of the MoMo webhook secret (or a leaked signed body) lets an attacker forge a VNPay or COD webhook. Combined with PAY-04 (the provider label is part of the dedup key) this is a complete forgery path: sign an arbitrary body with the shared secret, POST it to a *fresh* provider label, and drive any payment to any status.

**Violated invariant:** a provider's webhook credential must only authorise events from that provider.

**Root cause:** the secret is modelled per-service, not per-provider.

**Proposed fix:** `shop.payment.webhook.secrets.<provider>` (a map), the controller resolves the secret for the *registered* provider and rejects an unregistered provider with 404. The Stripe path already does exactly this (`service/WebhookEventService.java:75` uses `stripeProperties.webhookSecret()`).

**Reproduction test:** `test/.../webhook/PaymentWebhookControllerTest.java` — assert a MoMo-signed body is rejected on the VNPay path.

**Acceptance criterion:** each provider path verifies against that provider's own secret; an unregistered provider path is rejected.

---

### PAY-15 — MEDIUM — VNPay multiplies the amount by 100 unconditionally (100× overcharge on VND); MoMo truncates it; and neither provider ever contacts the PSP

**Evidence**
* `provider/VNPayProvider.java:41-47`:
  ```java
            String rawData = "vnp_Amount=" + amount.multiply(BigDecimal.valueOf(100)).longValueExact()
                    + "&vnp_Command=pay"
                    ...
            String paymentUrl = vnpayUrl + "?" + rawData + "&vnp_SecureHash=" + secureHash;
            log.info("Generated VNPay payment redirect URL for payment {}", paymentId);
            return new ProviderResult(vnpTxnRef, true);
  ```
  VND is a zero-decimal currency (`provider/StripeCurrencyUnits.java:10-12`), so `50000 VND` is sent as `5,000,000`.
* `provider/MoMoProvider.java:41-44`:
  ```java
            String rawSignature = "accessKey=" + accessKey
                    + "&amount=" + amount.longValue()
  ```
  `50000.99` → `50000`.
* `provider/MoMoProvider.java:46-47` and `provider/VNPayProvider.java:48-49` — no HTTP call to the PSP is made; the generated redirect URL is discarded because `ProviderResult` has no field for it (`provider/PaymentProvider.java:14-15`).
* `service/impls/PaymentServiceImpl.java:127-135` — `capture` still marks the payment `CAPTURED`.

**Actual behaviour:** with `shop.payment.provider=vnpay`, a customer is marked CAPTURED (and the order confirmed) without ever seeing a payment page; if the URL were surfaced, it would ask for 100× the amount. With `momo`, the amount in the signature is truncated.

**Violated invariant:** the amount presented to the customer must equal the amount the platform recorded.

**Root cause:** the local providers were written as demo stubs but are registered as production strategies and share the `capture → accepted → CAPTURED` contract with a real PSP.

**Proposed fix:** use `StripeCurrencyUnits.toMinor` (rename it to a currency-neutral `MoneyUnits`) for the conversion; extend `ProviderResult` with a `redirectUrl`/`clientSecret` and a `requiresAction` flag so a redirect provider keeps the payment `PENDING` until its callback; or remove MoMo/VNPay from the registry until they are real.

**Reproduction test:** `test/.../provider/VNPayProviderTest.java` — assert the amount encoded for VND 50000 is `50000` (today the test only asserts `providerEventId` is non-blank, `:25-33`).

**Acceptance criterion:** no provider sends an amount that differs from `payments.amount`; a redirect provider leaves the payment `PENDING` until the provider callback.

---

### PAY-16 — MEDIUM — No currency validation and no currency comparison on the webhook path

**Evidence**
* `dto/CreatePaymentRequest.java:15` — `@NotBlank @Size(min = 3, max = 3) String currency` (length only).
* `entity/Payment.java:30-32` — stored verbatim in `char(3)`.
* `service/WebhookEventService.java:195-198` — the amount check compares only the amount:
  ```java
        if (payload.amount() == null || payment.getAmount() == null
                || payload.amount().compareTo(payment.getAmount()) != 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, payload.amount(), payment.getAmount());
        }
  ```
* `service/impls/PaymentServiceImpl.java:110-117` — `samePayload` compares the currency case-sensitively.

**Actual behaviour:** a payload in a different currency but with the same numeric amount passes the amount check and drives the transition. `"usd"` and `"USD"` are distinct rows and distinct idempotency payloads.

**Violated invariant:** a payment is captured in exactly one currency, and the provider event must be in that currency.

**Root cause:** the currency is treated as a free-form label rather than a value object.

**Proposed fix:** normalise to upper case on write, validate against an ISO-4217 allow-list (at minimum the set in `provider/StripeCurrencyUnits.java:10-12` plus the two-decimal majors), and compare the currency in the webhook amount check.

**Reproduction test:** a webhook payload with `"currency":"EUR"` against a `"USD"` payment must be rejected with `PAY-5007` (no test covers this today).

**Acceptance criterion:** an unsupported currency is rejected at the API boundary; a currency mismatch on the webhook is rejected.

---

### PAY-17 — MEDIUM — Zero-decimal currencies silently round the amount, which then fails the webhook amount check

**Evidence**
* `provider/StripeCurrencyUnits.java:18-21`:
  ```java
      public static long toMinor(BigDecimal amount, String currency) {
          int digits = minorUnitDigits(currency);
          return amount.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact();
      }
  ```
* `provider/StripeCurrencyUnits.java:10-12` — VND, JPY, KRW, … are zero-decimal.
* `service/WebhookEventService.java:195-198` — the webhook compares the (rounded) major amount against the stored amount.

**Actual behaviour:** `CreatePaymentRequest` accepts `100.50 VND` (`@Digits(fraction = 2)` permits it). `toMinor` → `101`. Stripe charges 101 VND; the webhook reports 101; the comparison against the stored `100.50` fails → `AMOUNT_MISMATCH` → the payment can never be captured by webhook and the money (101 VND) is charged against a payment stuck in `FAILED_RETRYABLE`. The same rounding silently changes the charged amount.

**Violated invariant:** the amount charged must equal the amount recorded, and the amount recorded must be representable in the currency's minor unit.

**Root cause:** the rounding is applied silently at the provider boundary instead of being rejected at the domain boundary.

**Proposed fix:** validate the amount's scale against the currency at create time (reject a fractional amount for a zero-decimal currency), and make `toMinor` fail loudly (`ArithmeticException` → `PAY-5004`) instead of rounding when the value is not exactly representable.

**Reproduction test:** `test/.../provider/StripeProviderTest.java` — assert `capture(…, new BigDecimal("100.50"), "VND", …)` is rejected (today it silently charges 101).

**Acceptance criterion:** a zero-decimal currency rejects fractional amounts at the API boundary; no rounding occurs anywhere in the money path.

---

### PAY-18 — MEDIUM — Outbox rows parked as `FAILED` are never purged

**Evidence**
* `outbox/PaymentOutboxRelay.java:206-213` — the relay parks an exhausted row as `OutboxStatus.FAILED`:
  ```java
                if (e.getRetryCount() >= maxRetries) {
                    e.setStatus(OutboxStatus.FAILED);
  ```
* `outbox/OutboxRetentionScheduler.java:74-90` — the purge only removes `SENT` and `DEAD`:
  ```java
        int deletedSent = outboxRepo.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, ...);
        ...
        int deletedDead = outboxRepo.deleteByStatusAndUpdatedAtBefore(OutboxStatus.DEAD, ...);
  ```
* `utils/common-core/.../OutboxStatus.java:19-20` — the enum has both `FAILED` and `DEAD`; nothing in payment-service ever writes `DEAD`.

**Actual behaviour:** every outbox row that exhausts its retry budget stays in `outbox_events` forever. The table grows without bound — the very problem the retention scheduler was added to solve (`outbox/OutboxRetentionScheduler.java:17-21`).

**Violated invariant:** every terminal outbox row must be reclaimed by the retention policy.

**Root cause:** the scheduler was written against the media-service convention (`DEAD`) while this module's relay parks `FAILED`.

**Proposed fix:** either park exhausted rows as `DEAD` in `PaymentOutboxRelay.markFinalState` / `reclaimStaleSending`, or add a `deleteByStatusAndUpdatedAtBefore(FAILED, cutoff)` purge. Note that `FAILED` rows are the ones an operator needs — the policy should keep them for the DEAD window and then delete.

**Reproduction test:** `test/.../outbox/OutboxRetentionSchedulerTest.java` — assert a `FAILED` row older than the window is deleted.

**Acceptance criterion:** `outbox_events` row count is bounded by the retention window for every status.

---

### PAY-19 — MEDIUM — No production caller creates or captures a payment, so the confirm gate can never pass

**Evidence**
* `controller/PaymentController.java:38-45` (create), `:47-54` (capture) — both require the `order-service` client id.
* `order-service/.../client/PaymentServiceClient.java` — the only production client of payment-service; it calls only `GET /api/v1/payments/by-order/{orderId}/captured` (`:72`) and `POST /api/v1/payments/{id}/refund` (`:102`).
* Repo-wide `grep -rn "api/v1/payments" --include=*.java` (excluding `payment-service` and `.gitnexus`) → only the two calls above plus test stubs.
* `application.yml:47` — the default provider is `mock`.

**Actual behaviour:** no payment is ever created, so `findCapturedByOrderId` always returns empty and the order-service confirm gate always fails closed with `ORD-4012`; every order is auto-cancelled. The payment flow exists but is not wired into the checkout path.

**Violated invariant:** the order→payment saga must have a producer of the payment.

**Root cause:** the payment-creation trigger (the checkout service) was never implemented or was removed.

**Proposed fix:** implement the checkout → `POST /api/v1/payments` → `POST /api/v1/payments/{id}/capture` path in the owning service (with the amount sourced from the order total, not from the request), or make the Stripe flow self-driving.

**Reproduction test:** an end-to-end IT: create an order → assert a payment row is created → capture → assert the order reaches `CONFIRMED`.

**Acceptance criterion:** an order placed through the checkout API results in exactly one captured payment and a confirmed order.

---

### PAY-20 — LOW — The default provider is non-deterministic when the configured one is absent

**Evidence**
* `provider/PaymentProviderConfig.java:43-46`:
  ```java
        return all.stream()
                .filter(p -> p.name() != null && p.name().toUpperCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(all.get(0));
  ```
  `all.get(0)` is the Spring-injected list order (deterministic per context, but not tied to the configuration), and it throws `IndexOutOfBoundsException` if the list is empty.
* `provider/PaymentProviderFactory.java:45-55` — `providers.values().iterator().next()` on a `ConcurrentHashMap` has **no defined order**.

**Actual behaviour:** a typo in `shop.payment.provider` silently selects some provider; the service then behaves differently across restarts/versions.

**Proposed fix:** throw a clear `IllegalStateException` naming the configured provider when it is not registered; remove the `values().iterator().next()` fallback.

**Reproduction test:** `test/.../provider/PaymentProviderConfigTest.java` — a context with `shop.payment.provider=does-not-exist` must fail to start with a named message.

**Acceptance criterion:** an unknown `shop.payment.provider` prevents startup; the default is never chosen arbitrarily.

---

### PAY-21 — LOW — The public webhook endpoint has no body-size limit and no service-level rate limit

**Evidence**
* `application.yml:65-67` — `POST /api/v1/webhooks/payments/**` is `permitAll`.
* `webhook/PaymentWebhookController.java:33` — `@RequestBody byte[] rawBody` buffers the entire body in heap.
* `gateway-service/src/main/resources/application.yml:87-99` — the rate limiter exists at the gateway only; the payment-service port is directly reachable inside the cluster.

**Actual behaviour:** an anonymous caller can push large bodies at the payment-service port directly, and a body is buffered before the signature is checked (the signature check happens at `webhook/PaymentWebhookController.java:40`, after binding).

**Violated invariant:** a public endpoint must bound the resources an unauthenticated caller can consume.

**Proposed fix:** set `spring.servlet.multipart.max-request-size` / a `max-http-request-header-size`-equivalent for the raw body (or use a `ContentCachingRequestWrapper` with a cap), and mirror the gateway rate limit inside the service.

**Reproduction test:** a MockMvc test posting an oversized body and asserting a 413.

**Acceptance criterion:** a request body above the configured cap is rejected before allocation; the endpoint is rate-limited at the service.

---

### PAY-22 — LOW — Dead code and an unreachable status value

**Evidence**
* `service/PaymentWriter.java:58-62` — `markEventFailed` has no production caller (only `test/.../service/PaymentWriterTest.java:60-67`).
* `service/PaymentWriter.java:21-24` — `insert(Payment)` has no production caller (`insertIsolated` is used instead).
* `repository/PaymentEventRepository.java:14` — `existsByProviderAndProviderEventId` has no caller.
* `constant/PaymentStatus.java:9` — `TERMINAL_WEBHOOK_STATES` has no caller.
* `entity/PaymentEvent.java:18` — `STATUS_FAILED = "FAILED"` is only reachable through `markEventFailed`, so the `'FAILED'` value in the `payment_events` CHECK constraint (`changelog-001-payments.yaml:70`, widened at `changelog-004-...yaml:16-19`) is dead.

**Actual behaviour:** the codebase carries three mutation paths for the same table, which is exactly how the state divergence of PAY-11 happened.

**Proposed fix:** delete the unused methods/constant, or route them through the single transition service.

**Reproduction test:** none required (compile-time hygiene).

**Acceptance criterion:** no unused public method in `payment-service` without a caller (enforce with a static-analysis gate).

---

### PAY-23 — LOW — The Stripe api-version property is inert and always reports drift

**Evidence**
* `application.yml:58` `api-version: "2024-06-20"`.
* `config/PaymentStripeProperties.java:29-31`:
  ```java
      public boolean isVersionDriftedFromSdk() {
          return isConfigured() && !apiVersion.equals(com.stripe.Stripe.API_VERSION);
      }
  ```
* `provider/StripeProvider.java:40-44` logs a warning when drifted.
* `javap` on `~/.m2/repository/com/stripe/stripe-java/24.24.0/stripe-java-24.24.0.jar` → `public static final java.lang.String API_VERSION = "2023-10-16";`
* `provider/StripeProvider.java:41-42` — the warning text itself says "the SDK constant governs the wire (spec D1)".

**Actual behaviour:** the configured value is never applied to the wire; the drift check is permanently true and logs a misleading warning at every startup, training operators to ignore it.

**Violated invariant:** a configuration value that claims to pin a protocol version must either pin it or be removed.

**Root cause:** the stripe-java SDK does not allow a per-request version override; the property was kept for documentation.

**Proposed fix:** delete the `api-version` property and the drift check, or rename it to `api-version-observed` and log it as informational. Also align the pinned value with the SDK constant if it is kept.

**Reproduction test:** `test/.../config/PaymentStripePropertiesTest.java:52-68` — currently asserts the drift detection; it must be updated to assert the *absence* of a false drift signal.

**Acceptance criterion:** payment-service logs no drift warning at startup; the effective API version is documented once.

---

### PAY-24 — LOW — Unused imports (suppressed debt) in four provider classes

**Evidence** — `ConditionalOnProperty` is imported but never used:
`provider/MockProvider.java:3`, `provider/CodProvider.java:3`, `provider/MoMoProvider.java:5`, `provider/VNPayProvider.java:5`, `provider/StripeProvider.java:16`.

**Actual behaviour:** no functional impact — `config/checkstyle-suppressions.xml` suppresses `UnusedImports` for `payment-service/` ("TODO: pay down payment-service style debt"), so the build stays green. But it signals that these providers *were* meant to be conditional and the annotation was dropped — consistent with PAY-15 (stub providers always registered in production).

**Proposed fix:** remove the imports, or add the intended `@ConditionalOnProperty` to make MoMo/VNPay opt-in.

**Acceptance criterion:** `payment-service` passes `UnusedImports` with the suppression removed for that module.

---

## 11. Verdicts

### 11.1 Webhook signature verification — **PASS with two medium gaps**

* The **Stripe path is correct**: the official SDK verifies `t=…,v1=…` HMAC-SHA256 over `"<t>.<raw_body>"`, enforces a 300 s tolerance, uses a constant-time compare, and fails closed on a blank secret (`service/WebhookEventService.java:82-95`; `webhook/WebhookSignatureVerifier.java:109`, `:160-164`, `:186-188`). Tampering is proven to be rejected end-to-end (`test/.../StripeWebhookIT.java:296-310`).
* The **raw body is preserved** end-to-end (`webhook/PaymentWebhookController.java:33`, `@RequestBody byte[]`); nothing is parsed before verification.
* Gaps: (a) the non-Stripe path uses the **bare-hex** branch, which has **no timestamp and therefore no replay window** (`webhook/WebhookSignatureVerifier.java:112-120`) — PAY-13; (b) **one secret serves every non-Stripe provider** (`application.yml:48-51`) — PAY-14; (c) the `{provider}` path segment is part of the dedup identity, so a signed body can be replayed under a different label — PAY-04.
* The endpoint is **public** (`application.yml:65-67`, gateway `:70`); the HMAC is the only control, with no body-size cap and no service-level rate limit — PAY-21.

### 11.2 Duplicate-webhook handling — **MOSTLY SAFE at the store, UNSAFE at the effect**

* **Store level: SAFE.** The Stripe event id is persisted under `CREATE UNIQUE INDEX uk_payment_events_provider_event ON payment_events (provider, provider_event_id)` (`changelog-001-payments.yaml:72`), looked up via `repository/PaymentEventRepository.java:20`, and the insert race is caught at `service/WebhookEventService.java:122-125`. A redelivery of an already-`PROCESSED` event is a no-op (`:113-115`). Asserted in `test/.../StripeWebhookIT.java:312-330` and `test/.../PaymentFlowIT.java:134-151`.
* **Effect level: UNSAFE.** (i) The state machine allows self-transitions (`service/PaymentStateMachine.java:26-28`) so a re-processed event publishes a **second** lifecycle event (PAY-03); (ii) the dedup key includes the attacker-controlled provider label (PAY-04); (iii) the retry scheduler has no claim, so two replicas replay the same row concurrently (PAY-05); (iv) the bare-hex signature has no replay window (PAY-13).
* **Net:** a *duplicate delivery of the same Stripe event id to the same provider path* is safe. A *replay under a different provider label*, a *concurrent retry-scheduler replay*, or a *re-processing after a partial failure* is not.

### 11.3 Idempotency at the DB level — **PARTIAL**

| Idempotency key | Persisted? | UNIQUE constraint | Repository method | Verdict |
|---|---|---|---|---|
| Stripe event id | yes — `payment_events.provider_event_id` | **YES** — `changelog-001-payments.yaml:72` `uk_payment_events_provider_event (provider, provider_event_id)` | `repository/PaymentEventRepository.java:20` `findFirstByProviderAndProviderEventId` | **PASS** (with the PAY-04 caveat that the pair, not the id alone, is unique) |
| Payment idempotency key | yes — `payments.idempotency_key` | **YES but GLOBAL** — `changelog-001-payments.yaml:37` `uk_payment_idempotency_key (idempotency_key)`; the per-user index `changelog-006-payment-user-id.yaml:19-23` is **not** unique | `repository/PaymentRepository.java:16` `findByIdempotencyKey` / `:24` `findByIdempotencyKeyAndUserId` | **FAIL** — the constraint contradicts the per-user scoping the code assumes (PAY-07) |
| Stripe idempotency key (PSP-side) | n/a — passed as `RequestOptions.idempotencyKey` | n/a | `provider/StripeProvider.java:127-132` | **PASS** — but the PSP cache is 24 h and the local row keeps no provider reference (PAY-08, PAY-09) |
| Outbox lifecycle event | yes — `outbox_events` | **NO** uniqueness on `(aggregate_id, event_type)`; only `event_id` (`changelog-002-payment-outbox.yaml:17`) and that id is a fresh random UUID per publish (`outbox/PaymentEventPublisher.java:41`) | `outbox/OutboxEventRepository.java:20` | **FAIL** — duplicate lifecycle events are storable and publishable (PAY-03) |

### 11.4 Severity-ranked summary

| ID | Severity | One-line |
|---|---|---|
| PAY-01 | CRITICAL | Capture marks `CAPTURED` with no money moved; the Stripe intent is created with `confirm=false` and its `client_secret` is discarded, so it can never be confirmed |
| PAY-02 | CRITICAL | An unknown provider name silently falls back to the default → a refund can "succeed" against the wrong PSP |
| PAY-03 | HIGH | Self-transitions + unconditional publish → duplicate `payment.captured.v1` on every re-processing |
| PAY-04 | HIGH | The dedup key contains the attacker-controlled `{provider}` path segment → one signed body replayed across provider labels |
| PAY-05 | HIGH | The webhook retry scheduler has no claim/lease → two replicas replay the same event |
| PAY-06 | HIGH | No Saga compensation: a webhook after order cancellation captures the payment and never refunds it |
| PAY-07 | HIGH | The global unique index on `idempotency_key` contradicts the per-user lookup → cross-tenant key collision and blocking |
| PAY-08 | HIGH | The Stripe intent id is never persisted → refunds rely on the eventually-consistent Search API and usually fail |
| PAY-09 | HIGH | A Stripe timeout is mapped to "provider rejected" → a possibly-created intent is reported as a failure |
| PAY-10 | HIGH | The PSP call is inside the DB transaction with no client timeout → 80 s transaction/connection held |
| PAY-11 | MEDIUM | The REST path bypasses the state machine and never sets `refunded_amount` |
| PAY-12 | MEDIUM | Optimistic-lock failures surface as 500, after the provider side effect |
| PAY-13 | MEDIUM | The bare-hex signature branch has no timestamp → unbounded replay |
| PAY-14 | MEDIUM | One webhook secret for all non-Stripe providers |
| PAY-15 | MEDIUM | VNPay sends 100× the amount on VND; MoMo truncates; neither contacts the PSP |
| PAY-16 | MEDIUM | No currency whitelist/normalisation and no currency check on the webhook |
| PAY-17 | MEDIUM | Zero-decimal currencies silently round, breaking the webhook amount check |
| PAY-18 | MEDIUM | Outbox `FAILED` rows are never purged |
| PAY-19 | MEDIUM | No production caller creates or captures a payment → the confirm gate can never pass |
| PAY-20 | LOW | The default provider is chosen non-deterministically |
| PAY-21 | LOW | The public webhook endpoint has no body-size cap and no service-level rate limit |
| PAY-22 | LOW | Dead code (`markEventFailed`, `insert`, `existsByProviderAndProviderEventId`, `TERMINAL_WEBHOOK_STATES`) |
| PAY-23 | LOW | The Stripe api-version property is inert and always reports drift (SDK constant is `2023-10-16`) |
| PAY-24 | LOW | Unused `ConditionalOnProperty` imports in five provider classes (suppressed debt) |

**Totals: 2 CRITICAL, 8 HIGH, 9 MEDIUM, 5 LOW.**

---

*End of payment-service source review. 80/80 scoped files read in full; no application source file was modified by this review.*
