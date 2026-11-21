# QA / Test-Suite Forensics — petproject @ f242158265163d65ef73d378331d2bd7a92479ee

Independent auditor pass (no build, no docker, read-only on source/tests). Raw per-file inventory:
[evidence/qa/test-inventory.md](../evidence/qa/test-inventory.md). All `file:line` references verified by reading.

Verdict in one line: the money-critical invariants (last-unit oversell, coupon quota, webhook replay, refund
idempotency, outbox claim) have genuinely strong Testcontainers-backed tests — but CI's *reporting* layer is
partly self-certifying (harness suites), there is **no coverage gate at all** (jacoco is dead config), one
inventory IT is silently dead under plain `mvn test`, and the "concurrency" CI job does not run the tests its
name implies.

---

## Findings

### QA-1 — inventory-service `*IT` classes silently excluded from plain `mvn test` (dead test)
- Severity: **P1** · Confidence: high
- Files: `inventory-service/pom.xml` (no `maven-surefire-plugin` section at all — verified by grep),
  contrast `order-service/pom.xml:145-157` / `payment-service/pom.xml:143-153` /
  `promotion-service/pom.xml:130-140` (+8 more service poms) which add `<include>**/*IT.java</include>`;
  `inventory-service/src/test/java/com/shop/inventoryservice/repository/ReservationsOrderIdIndexIT.java`;
  root `pom.xml:486-550` (failsafe `it` profile activates only if `src/it/java` exists — no module has it).
- Description: Surefire default includes are `Test*`, `*Test`, `*Tests`, `*TestCase`. `ReservationsOrderIdIndexIT`
  (a `@DataJpaTest` + Testcontainers test verifying the `reservations(order_id)` index) matches none. The root
  failsafe profile that would run `*IT` never activates (`src/it/java` absent everywhere). So CI job
  "Build, Checkstyle & Unit Tests" (`./mvnw -T1C test`, ci.yml:34) and any local `mvn verify` **never execute it**.
- Impact: an index/schema regression in reservations-by-order lookups would not be caught by the only maven job
  whose output is independently interpretable. The class runs only if the harness `integration` suite is invoked
  (its `-Dtest=*IT` selector overrides includes), i.e. only via self-reported harness evidence (see QA-3).

### QA-2 — No coverage gate; jacoco (and OWASP dependency-check) are dead configuration
- Severity: **P1** · Confidence: high
- Files: root `pom.xml:335-365` (jacoco in `pluginManagement` only: `prepare-agent` + `report`, **no `check`
  execution, no thresholds**; excludes at `pom.xml:340-348`), root `pom.xml:366-384` (owasp dependency-check,
  also `pluginManagement` only). Grep over all 15 module poms: **zero** references to `jacoco-maven-plugin` or
  `dependency-check-maven` in any `<build><plugins>`. Sonar only via manual `scripts/run-sonar-scan.sh`
  (requires `.env.sonar`; not in CI).
- Description: pluginManagement alone binds nothing. Neither jacoco nor dependency-check ever executes in any
  module build; there is no coverage threshold anywhere and no SCA gate in CI.
- Impact: any statement like "tests cover X%" is unevidenced; regenerating coverage requires ad-hoc manual runs.
  The only enforced quality gate is checkstyle at `validate` (pom.xml:313-334, `failOnViolation=true`,
  `includeTestSourceDirectory=false` — style only, main sources only).

### QA-3 — CI "concurrency" and "integration" jobs are harness-driven, self-selected, and not independent evidence
- Severity: **P1** · Confidence: high
- Files: `.github/workflows/ci.yml:42-57` (`python3 production-audit/bin/harness test --suite concurrency`),
  `ci.yml:59-74` (`--suite integration`); `production-audit/harness/verification/suites.py:10-16` (suite
  definitions are **filename patterns**: concurrency = `*Concurrency*Test, *Concurrency*IT, *Race*Test,
  *Parallel*Test`; integration = `*IT`), `suites.py:29-45` (command = `./mvnw verify -Dtest=<selector>
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<selector>`), `gates.py:44-83` (PASS = maven exit code 0),
  `gates.py:87-109` (`tests_run` scraped from the maven log by regex).
- Description: These jobs are the project auditing itself: the pass/fail signal is a maven exit code filtered
  through the harness's own bookkeeping. Because `-DfailIfNoSpecifiedTests=false` is set, a renamed class or a
  broken pattern yields a **green suite with 0 tests run**. The `-Dit.test` selectors are inert (failsafe never
  activates, QA-1 context). The `production-audit/tests/{concurrency,integration,e2e,fault-injection,regression}`
  directories are **empty** — the suites select from src/test by name only.
- Impact: "concurrency-tests ✓ / integration-tests ✓" in CI must not be treated as evidence that those suites
  executed anything. Only the harness-independent maven steps (`ci.yml:31` validate, `ci.yml:34` test) and the
  docker builds (ci.yml:91,94) are directly interpretable.

### QA-4 — The "concurrency" suite's selector misses the strongest money-race tests
- Severity: **P2** · Confidence: high
- Files: `suites.py:14` patterns vs actual class names.
- Description: Not matched by `*Concurrency*Test|*Race*Test|*Parallel*Test`:
  `InventoryReserveIdempotencyTest` (the last-unit oversell race — inventory-service/.../InventoryReserveIdempotencyTest.java:250-291),
  `PaymentIdempotencyIT` (payment create race), `IdempotencyClaimIT` (order idempotency claim race),
  `ReserveFlowIT` (8-thread per-user-limit race), `CartClearLockIT`, `CancelOutsideTransactionIT`.
  The IT-named ones fall through to the integration suite (`*IT`); `InventoryReserveIdempotencyTest` and other
  `*Test`-named race tests are covered **only** by the generic unit job (ci.yml:34, where they do run because
  surefire defaults match `*Test`).
- Impact: the CI job labels create a false mental model ("concurrency job proves races safe"). Green CI is not
  misleading here only because the tests happen to run elsewhere — but any audit that credits the concurrency
  job is wrong, and a rename to `...RaceIT` would make the concurrency job silently empty (QA-3 interaction).

### QA-5 — Tautological mock "concurrency" test for inventory reserve; near-miss evidence
- Severity: **P2** · Confidence: high (test text fully read)
- Files: `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplReserveConcurrencyTest.java:38-68`
  (repo mocks; `atomicReserve` stubbed to always return 1 at :60-61), `:80-107` (`fiftyConcurrentReserves...`:
  50 threads, asserts only `verify(...times(concurrent))` + `failures==0`), `:109-118` (zero-return reject).
- Description: The 50-thread test exercises nothing real: no DB, no decrement, no stock assertion. It cannot
  detect oversell, double-decrement, or lost updates — it proves the service calls a method 50 times. The real
  oversell evidence exists separately (QA-matrix R2), but this class invites mis-citation as "oversell tested
  with 50 concurrent threads".
- Impact: weak signal inflating apparent concurrency coverage for inventory; must not be used as oversell proof.

### QA-6 — Race tests are real-Postgres but small-N (2 threads), single JVM, single instance
- Severity: **P2** · Confidence: high
- Files: `InventoryReserveIdempotencyTest.java:216,255` (callers=2), `CampaignQuotaConcurrencyIT.java:153`
  (threads=2; but `ReserveFlowIT.java:258` threads=8), `PaymentIdempotencyIT.java:96-97` (2),
  `RefundConcurrencyIT.java:73` (2), `IdempotencyClaimIT.java:52` (2 threads × 25 iterations),
  `OrderOutboxClaimConcurrencyIT.java:64-99` (2 latched threads).
- Description: All DB-backed races synchronize with `CyclicBarrier`/`CountDownLatch` inside one JVM against one
  Postgres container. No test drives N≫2 (e.g. 50-200 connections), no test runs two application instances
  (multi-pod), and no test intentionally interleaves transaction phases (e.g. hold→commit racing expiry sweeper).
  The DB unique/FOR UPDATE constraints they verify are instance-agnostic, which is the actual protection — but
  the *demonstrated* race window is narrow.
- Impact: races that depend on timing across instances or on the sweeper running concurrently would not be
  exposed by these tests; confidence in the invariants rests on the schema constraints, not the race coverage.

### QA-7 — No inventory expiry-vs-commit concurrency test; sweeper tested only sequentially
- Severity: **P2** · Confidence: high
- Files: sweeper sequential: `InventoryReserveIdempotencyTest.java:336-354` (expire row via JDBC, then call
  `cleanupScheduler.releaseAllExpiredReservations()`); commit idempotency sequential: `:293-313`; release:
  `:315-334`. Promotion equivalent sequential: `ReserveFlowIT.java:305-343`. Repo-wide grep for concurrent
  sweeper tests: none.
- Description: Nothing races `commit(reservationId)` against the expiry sweeper releasing the same hold (the
  classic "customer pays exactly as the hold lapses" window), and nothing tests commit arriving after the row
  is already EXPIRED. Related: order-side late confirm after release-committed is covered
  (`ConfirmOrchestrationIT.java:361-382`) but not the inventory-side sweeper interleave.
- Impact: a sweep/commit double-release or lost release bug would pass the suite. This is the single most
  concrete missing race in the money path.

### QA-8 — All full-context ITs run with security disabled; authz is verified only in slices/unit tests
- Severity: **P2** · Confidence: high
- Files: `order-service/.../support/AbstractOrderServiceIT.java:39` (`@AutoConfigureMockMvc(addFilters = false)`),
  `:108` (`shop.security.enabled=false`), `:105-107` (WireMock keycloak issuing dummy tokens); all 11
  `Abstract*IntegrationTest` bases import `common-spring/test/TestSecurityConfig` and set
  `shop.security.csrf-disabled=true` (e.g. inventory `AbstractIntegrationTest.java:49-51`, payment
  `AbstractIntegrationTest.java:58-60`); `payment AbstractIntegrationTest.java:61` pins provider to `mock`.
- Description: No `@SpringBootTest` test exercises the real security filter chain of a money service; the
  webhook endpoints are signature-authenticated (that is tested) but everything else is filterless in ITs.
  Authorization evidence lives in @WebMvcTest slices and dedicated unit tests
  (`OrderTrackingAuthorizationTest`, `SecurityFilterChainIntegrationTest`, `DeadLetterReplayEndpointAuthorizationTest`).
- Impact: a routing/security-config regression that only manifests with filters on would keep ITs green;
  cross-cutting "can user A confirm user B's order" scenarios are only proven at unit level.

### QA-9 — Stripe never contacted by the repo test suite; VNPay/MoMo only mock-level
- Severity: **P2** · Confidence: high
- Files: simulation strategy: `StripeProviderTest.java:36-44` (`mockStatic(PaymentIntent)`, `mockStatic(Refund)`),
  `StripeWebhookIT.java:102-103` (`sk_test_it_no_network`, `whsec_it_stripe_test`), `:333-359`
  (`MockedStatic<PaymentIntent>` for create idempotency), `payment AbstractIntegrationTest.java:61`
  (`shop.payment.provider=mock`), `support/ScriptedPaymentProvider.java` (scriptable fake PSP used by
  RefundConcurrencyIT/PaymentCaptureConfirmationIT). Real-network Stripe tooling exists only in the audit
  harness (`production-audit/harness/stripe/sandbox.py:14` → `https://api.stripe.com`) and is **not wired into
  CI** (no e2e job; `tests/e2e/` directory is empty). `VNPayProviderTest`/`MoMoProviderTest` are pure unit tests.
- Impact: no evidence exists at any automated level that the real Stripe API contract (param names, idempotency
  keys, signature header handling of the *live* SDK versions) is honored; provider regressions would only
  surface in production or a manual harness run.

### QA-10 — Webhook signature verification IS tested with real cryptography (positive assurance)
- Severity: **P3 (note)** · Confidence: high
- Files: `payment-service/.../webhook/WebhookSignatureVerifierTest.java:20-28` (computes HmacSHA256 itself;
  rejects null/blank/short/prefixed/non-hex/wrong-secret/tampered; `:112-175` Stripe `t=...,v1=...` format with
  expired/future-timestamp windows), `StripeWebhookIT.java:242-250` (signs with Stripe SDK
  `Webhook.Util.computeHmacSha256`), `:289-305` (tampered payload under valid-looking signature → 401 `PAY-5005`,
  no DB row), `service/WebhookEventServiceStripeTest.java:249-322` (forged/missing/misconfigured secret → 401).
- Impact: none (strength); listed to prevent the audit from assuming signature checks are skipped. Replay
  window and event-id dedup are both covered (StripeWebhookIT:307-325, PaymentFlowIT #2/#3).

### QA-11 — Assumption-based self-skips in contract tests (silent, environment-dependent)
- Severity: **P3** · Confidence: high
- Files: `utils/common-kafka/.../KafkaTopicContractTest.java:32,93`, `KafkaPropertiesTopicsRemovedTest.java:26`,
  `KafkaListenerGroupIdContractTest.java:26`, `utils/common-spring/.../ProductionEnvTemplateTest.java:32,38,64`
  (`Assumptions.assumeTrue(repoRoot != null / Files.exists(compose))`).
- Description: These contract tests abort silently when the repo root or `docker-compose.yml` is not resolvable
  from the test's working directory. No `@Disabled`, `@EnabledIf`, `@Tag`, `excludedGroups`, or
  `maven.test.skip` exists anywhere (verified by grep over all sources and poms); the only scripted skip is
  `start-docker.sh:91` (`-DskipTests jib:dockerBuild`, local image build, not CI).
- Impact: minor; the docker-compose contract checks can vanish depending on launch directory without any red
  signal beyond a "skipped" count nobody reads.

### QA-12 — "Unit test" CI job actually executes all Testcontainers ITs (mislabel, docker dependency)
- Severity: **P3** · Confidence: high
- Files: `ci.yml:33-34` (`./mvnw -T1C test`) + service-pom surefire includes (`order-service/pom.xml:154` et al.)
- Description: Because `**/*IT.java` is a surefire include in 11 service poms, the job named "Build, Checkstyle &
  Unit Tests" boots PostgreSQL/Kafka/Redis/Elasticsearch containers and runs the full IT corpus in parallel
  (`-T1C`). Positive: CI genuinely exercises the ITs (GH runners have Docker) with no compose dependency and no
  hidden DB sharing (QA-13). Negative: no isolation of fast unit feedback; a Docker/Testcontainers infrastructure
  failure presents as a "unit test" failure; inventory's `*IT` is the one exception (QA-1).
- Impact: signal-separation and runtime cost; no correctness impact.

### QA-13 — No shared/dev-DB coupling: tests are hermetic (positive assurance)
- Severity: **P3 (note)** · Confidence: high
- Files: zero `application*.yml` in any service `src/test/resources` (only fixture yaml:
  `auth-service/src/test/resources/security-filter-chain-fixture.yml`, changelog seeds, and
  `utils/common-spring/src/test/resources/application.yml` with no datasource). All contexts get datasource URLs
  from `@DynamicPropertySource` + Testcontainers (inventory `AbstractIntegrationTest.java:41-45`, promotion
  `:35-39`, payment `:47-51`, order `AbstractOrderServiceIT.java:87-89`, search `AbstractSearchIntegrationTest.java:44-45`).
  Main configs default to compose (`order-service/src/main/resources/application.yml:8`
  `jdbc:postgresql://localhost:5432/orderservice`) but no test consumes the default;
  `auth SecurityFilterChainIntegrationTest.java:38-44` excludes DataSource autoconfig entirely.
- Impact: the classic "green only because dev compose is up" failure mode is **not present**; the actual
  environmental dependency is a working Docker daemon (loud failure, not silent green).

### QA-14 — Test-count inflation: contract/i18n/mapper tests are ~1 in 6 of the suite
- Severity: **P3** · Confidence: medium (classification by name/content, not full read of all)
- Files: e.g. `MediaI18nKeysTest`, `ProductI18nKeysTest`, `RatingI18nKeysTest`, `PromotionI18nKeysTest`,
  `MediaErrorCodeI18nCoverageTest`, changelog contract tests (`PaymentCurrencyChangelog*`,
  `ShippingChangelog*`, `ShipmentEventStatusChangelogTest`, `PaymentStatusColumnWidthContractTest`),
  mapper field tests, 9× `TestLiquibaseConfig` fixtures, `MockProviderTest` (trivial provider smoke).
- Description: These assert resource bundles/changelog XML/DTO shape. They add green rows, not money-path
  assurance. Roughly 60-70 of ~406 test classes are contract/support-grade.
- Impact: headline test counts overstate behavioral coverage; harmless otherwise.

### QA-15 — Consumer-side redelivery dedup not demonstrated end-to-end
- Severity: **P2** · Confidence: medium-high
- Files: `order-service/.../kafka/PaymentLifecycleConsumerTest.java:56-93` (dispatch only; verify single call,
  poison dead-letter, already-transitioned swallow); `utils/common-kafka` framework tests (dead-letter, typed
  bind, offset replay, publish ledger `OutboxPublishLedgerTest.java:11-49`); producer-side claim/ledger strong
  (`OrderOutboxClaimConcurrencyIT.java:52-174`, per-service `*OutboxClaimConcurrency*` in payment/inventory/
  promotion/rating/media/product/shipping; recovery ITs per service).
- Description: No test delivers the *same Kafka record twice* through a real listener to prove the idempotent
  confirm key (`payment-confirm-<paymentId>`, PaymentLifecycleConsumerTest:63) absorbs redelivery at the
  consumer boundary; dedup after a second delivery is only implied by service-level idempotency tests
  (`OrderServiceImplTest.java:221-225` cached replay) and webhook-level dedup tests.
- Impact: at-least-once dedup rests on the idempotency-service contract; the missing link is a duplicated
  message test, small but real.

---

## Release-blocking invariant evidence matrix

Legend: STRONG = asserts final DB/provider state under real infrastructure with concurrency or replay;
MEDIUM = real state but sequential or slice-level; WEAK = mock-echo/structural only.

| # | Invariant | Existing test evidence (file:line) | Strength | What is missing |
|---|---|---|---|---|
| R1 | Checkout idempotency (order create/confirm) | `IdempotencyClaimIT.java:46-88` (2-thread claim race ×25, exactly-one-owner, DB insert-if-absent) · `ConfirmOrchestrationIT.java:264-304` (duplicate confirm → 1 downstream commit set, 1 event, idempotency row persisted) · `OrderServiceImplTest.java:221-225` (cached replay) · `PaymentIdempotencyIT.java:35-114` (payment create; 2-thread race :93-114) | STRONG (service/DB level) | No HTTP-level duplicate POST with Idempotency-Key header through real filters (filters off, QA-8); no N≫2 retry storm; no two-instance claim demo |
| R2 | No oversell on last unit | `InventoryReserveIdempotencyTest.java:250-291` (2 concurrent last-unit reserves → exactly 1 success + `STOCK_INSUFFICIENT`; DB quantities asserted :286-290) · DB constraint proof `:356-379` · atomic decrement path `commit/release idempotency :293-334` | STRONG but N=2 | Larger storm (50+ real threads); reserve racing expiry-sweeper (QA-7); multi-instance demo; the mock 50-thread test (QA-5) proves nothing and must not be cited |
| R3 | No coupon quota breach | `CampaignQuotaConcurrencyIT.java:148-195` (2-thread last-use → 1 success, `used_count=1` in DB) · `ReserveFlowIT.java:253-302` (8 threads, per_user_limit=3 → exactly 3, rejects PRO-7006/7011, DB counts asserted) · budget gate `ReserveFlowIT.java:236-250` · expiry returns quota `:305-343` | STRONG | last-coupon storm N≫2; budget race across concurrent commit/release; campaign end-time transition race; multi-instance |
| R4 | Payment webhook replay safety | `StripeWebhookIT.java:307-325` (replay → 200 ack, exactly 1 event row, no state change; real HMAC signature) · `PaymentFlowIT.java:134-152` (#2 replay → 1 row) · bad signature `PaymentFlowIT:153-167`, `StripeWebhookIT:289-305` | STRONG | Replay racing a concurrent live webhook for the same payment (two in-flight POSTs); Kafka `payment.captured.v1` duplicate-delivery test (QA-15) |
| R5 | Refund idempotency | `RefundConcurrencyIT.java:65-103` (2 concurrent refunds → exactly 1 provider call, 1 refund row, PARTIALLY_REFUNDED 60.00) · same-approval replay `:127-140` and `:143-170` · provider-refund ledger non-double-count `WebhookRefundLedgerIT.java:66-121` | STRONG | Refund racing an inbound refund webhook for the same payment; refund while capture in flight; timeout/retry of provider call mid-refund (scripted delay used only for serialization) |
| R6 | Outbox at-least-once dedup / no double publish | `OrderOutboxClaimConcurrencyIT.java:52-174` (FOR UPDATE claim; peer locked out; second row while head locked) + same pattern for payment/inventory/promotion/rating/media/product/shipping (`*OutboxClaimConcurrency*`) · stranded-claim recovery `PaymentOutboxStrandedClaimIT`, `*OutboxRelayRecoveryIT` ×7 · aggregate ordering ITs ×7 · ledger bookkeeping `OutboxPublishLedgerTest.java:11-49` | STRONG (relay side) | Consumer-side duplicate delivery (QA-15); end-to-end Kafka round trip per service (relays assert DB/claim state, not a real broker consumer redelivering) |

Matrix coverage verdict: R1-R6 all have at least one STRONG evidence source that a reasonable auditor can cite.
The residual risks are concurrency breadth (QA-6, QA-7), boundary layers (QA-8, QA-15), and real-PSP exposure
(QA-9).

## CI: exactly what runs and what does not (task 3/8 summary)

Runs in CI (`.github/workflows/ci.yml`, single file, no other workflows):
1. `validate-and-test` (ci.yml:16-40): `./mvnw -T1C validate` (checkstyle+enforcer), `./mvnw -T1C test`
   (surefire = ALL tests incl. Testcontainers ITs for the 11 poms with the include; inventory `*IT` excluded, QA-1),
   harness `comments scan` (ci.yml:37), python unittest of the harness itself (ci.yml:40).
2. `concurrency-tests` (ci.yml:42-57): harness suite → `mvn verify -Dtest=*Concurrency*Test,*Concurrency*IT,*Race*Test,*Parallel*Test` (self-reported, QA-3/4).
3. `integration-tests` (ci.yml:59-74): harness suite → `mvn verify -Dtest=*IT -Dit.test=*IT` (self-reported, QA-3).
4. `container-build` (ci.yml:76-94): docker builds **gateway and order images only**.
5. `kubernetes-validation` (ci.yml:96-127): promtool, amtool, kubeconform + harness `k8s validate`.
6. `alerting-validation` (ci.yml:128-148): harness `alerts validate` (exit 2 tolerated without credentials).
7. `notify-telegram` (ci.yml:150-172).

NOT run in CI: failsafe (dead profile, QA-1), any docker-compose stack smoke, e2e (dir empty), Stripe sandbox /
any real PSP call, load tests (`load-tests/`), OWASP dependency-check, Sonar, jacoco (nothing, QA-2), image
builds for the other 11 services. Hidden-skip inventory: zero `@Disabled`/`@Tag`/`@EnabledIf`/`excludedGroups`;
`Assumptions.assumeTrue` self-skips at the five util locations (QA-11); `-DskipTests` only in
`start-docker.sh:91`.

The harness steps (ci.yml:37,40,57,74,126,142) execute the repo's own `production-audit/bin/harness`; their
PASS/FAIL and test counts are produced by that same codebase (suites.py/gates.py) and are **NOT independent
evidence** for this audit. Only ci.yml:31/34 (raw maven) and ci.yml:91/94 (raw docker) are externally
interpretable.

## Files not read (honesty list)

Fully read (≥ method-level): inventory AbstractIntegrationTest, promotion AbstractIntegrationTest, payment
AbstractIntegrationTest, AbstractOrderServiceIT, InventoryServiceImplReserveConcurrencyTest,
InventoryReserveIdempotencyTest, ReservationServiceImplTest, CampaignQuotaConcurrencyIT, ReserveFlowIT,
StripeWebhookIT, payment webhook/WebhookSignatureVerifierTest, RefundConcurrencyIT, PaymentIdempotencyIT,
IdempotencyClaimIT, OrderOutboxClaimConcurrencyIT, CartClearLockIT, OrderCreateSagaTest,
SecurityFilterChainIntegrationTest (partial), PaymentLifecycleConsumerTest (core), OutboxPublishLedgerTest,
suites.py, gates.py (head), ci.yml, root pom (build/profiles), 11 service poms (surefire sections).
Skimmed via grep (assertion/stub lines): OrderServiceImplTest, ConfirmOrchestrationIT, WebhookEventServiceStripeTest,
StripeProviderTest, guard/properties tests, MockProviderTest, CampaignReserveTest, PaymentFlowIT /
WebhookRefundLedgerIT / PaymentCaptureConfirmationIT / CapturedPaymentLookupIT method lists,
InventoryLifecycleIdempotencyTest / ReleaseCommittedTest / CancelOutsideTransactionIT method lists.
Not read at all: ~370 remaining test classes (controller slices, gateway WebFlux suites, shipping/tax/search/
media/notification/rating/product bodies, most utils tests), `docker-compose*.yml`, `load-tests/`, `k8s/`,
`docs/`, `mock-services/mock-payment-provider/server.js`, production-audit beyond harness verification/suites,
`scripts/run-sonar-scan.sh` beyond grep. Classification of unread files is annotation/name-based (documented in
the inventory file) and must not be cited as content-level review.
