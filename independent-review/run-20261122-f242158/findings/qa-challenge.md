# QA Challenge — Independent Verification of P0/P1 Findings (run-20261122-f242158)

- **Auditor:** reserved independent QA challenger. Revision `f242158` (branch audit/production-hardening-run-20261118-a2b6e6fc).
- **Method:** zero-trust re-derivation. Every claim below was re-verified by personally reading the cited sources plus callers/config/tests. I did not trust the other findings, docs, or `production-audit/`. No maven, no docker, no application source modified; this file is the only artifact written.
- **Note on inputs:** `findings/qa-test-forensics.md` and `evidence/load/*` were both read in full (qa-test-forensics.md appeared in the findings directory during this session).
- **Verdict vocabulary:** CONFIRMED / PARTIALLY_CONFIRMED / REFUTED / NEEDS_RUNTIME_VERIFICATION (with exact repro steps). "I read" = my own tool-read of the file at the named lines.

---

## 1. ORCH-P0 — Business 409 trips `@CircuitBreaker` on `InventoryServiceClient.reserve` (checkout dead platform-wide)

**Claim (restate).** `@CircuitBreaker(name="inventoryService")` wraps `reserve(...)`, which rethrows business 409s (`StockReservationFailedException`) as exceptions; no `ignoreExceptions`/`recordExceptions` exists anywhere, so a sellout of one hot SKU (409 INV-3002 stream) opens the breaker and fast-fails ALL checkouts for ALL products — as observed live at 19:20–19:22 UTC.

**Verdict: CONFIRMED** (mechanism re-derived from source; live incident classified NEEDS_RUNTIME_VERIFICATION only in the sense that I could not re-run it — the orchestrator's own runtime evidence plus source are jointly sufficient).

**Evidence I personally read:**
- `order-service/src/main/java/com/shop/orderservice/client/InventoryServiceClient.java:39` — `@CircuitBreaker(name = "inventoryService")` on `reserve`; `:49-57` catches `HttpClientErrorException` and **rethrows** `StockReservationFailedException` for 409/404 (`:52`) and `BusinessException` otherwise — the exception escapes the advised method, so resilience4j's default `recordFailurePredicate` counts it.
- `order-service/.../exception/StockReservationFailedException.java:8` — plain `extends RuntimeException` (irrelevant to the breaker either way: nothing ignores it).
- `utils/common-config/src/main/resources/application-shared.yml:194-204` — the **only** `resilience4j.circuitbreaker` config in the repo: COUNT_BASED/100, minimum calls 20, failure rate 50%, open 10s, half-open 5. **No `ignoreExceptions`, no `recordExceptions`, no instance sections.**
- Repo-wide sweeps I ran myself: `ignoreExceptions|recordExceptions|recordFailure|record-failure` over all files → zero hits in any yml/properties/Java config (only gateway's own filter code and production-audit prose). `resilience4j` in `*.properties` → zero. `*.yml` `circuitbreaker` → only `application-shared.yml:195` and the alert expression `config/observability/alertmanager/rules/runtime-alerts.yml:61`. `CircuitBreakerConfigCustomizer|CircuitBreakerCustomizer|CircuitBreakerRegistry` over `*.java` → the only programmatic registry is the **gateway's separate SCG filter** (`gateway-service/.../resilience/DownstreamResilienceConfiguration.java:37-38`), which is not the annotation aspect; no `Customizer` bean exists anywhere, including tests.
- The exact incident response shape: `OrderCreateSaga.java:191-208` — when the breaker-open `CallNotPermittedException` leaves `reserve`, the saga catch (`:191`) does not match `StockReservationFailedException`/`BusinessException`, so `:208` wraps `ex.getMessage()` (= "CircuitBreaker 'inventoryService' is OPEN and does not permit further calls") into `ORDER_RESERVATION_FAILED`. `ErrorCode.java:74` = `ORD-4007`. This explains **why the client saw 409 ORD-4007 containing breaker text** instead of the clean 503 that `DownstreamCircuitBreakerExceptionHandler.java:22-30` would produce — the exception never reaches the controller advice. The orchestrator's incident line at breaker-incident.md:8-9 is byte-consistent with this path.

**Which other clients share the pattern (corrected inventory):** `@CircuitBreaker` exists in exactly 7 classes (grep of the annotation import): order-service `ProductServiceClient.java:28`, `InventoryServiceClient.java:39`, `PaymentServiceClient.java:61,67,125,130` (4 annotations), `TaxServiceClient.java:34`, `PromotionServiceClient.java:49`; rating-service `EligibilityClient.java:34`; media-service `MediaReferenceClient.java:31`. Corrections to the orchestrator's list:
- **`PaymentServiceClient.findCapturedByOrderId` (`:61`→`:96-115`) is INERT, not a trip risk**: `lookupPayment` catches **every** `RestClientException` (including 4xx/5xx/transport) and returns `Optional.empty()` (`:109-114`); the breaker never observes a failure. The orchestrator listed `:61` as unprotected — it is the opposite failure mode (breaker that can never open).
- **`refundByOrderId` (`:125,:130`) CAN trip on business 4xx**: `refund` → `findRefundableByOrderId` (`:84-89` maps non-404 `HttpClientErrorException` → `remoteBusinessException`, rethrown) and the refund POST (`:164-174`) throws on 4xx/5xx. Aggravating nuance: `refund()` calls `findRefundableByOrderId` via `this` — **Spring proxy self-invocation**, so the inner `@CircuitBreaker` (`:67`) is bypassed and only the outer one records.
- **order-service has NO shipping client and NO rating client** (shipping interactions are Kafka-only; rating calls order-service, not vice versa). The orchestrator's "shipping/rating in order-service" is wrong.
- **No breaker exists in product-service's `InventoryReservationClient`**, payment/tax/inventory/shipping/search services at all (grep) — those hops have the inverse exposure (no short-circuit protection), not the trip risk.
- Aggravators the incident report missed: `ProductServiceClient.getProduct` **404 → BusinessException escapes** (`ProductServiceClient.java:37-39`) — a burst of checkouts over stale carts containing a deleted product trips `productService` the same way; `PromotionServiceClient.reserve` maps any coupon 4xx to a thrown `ORDER_PROMOTION_INVALID` (`PromotionServiceClient.java:62-64`) — invalid-coupon abuse traffic trips `promotionService`, disabling coupons platform-wide (only when enabled — mitigated by `order-service/application.yml:60` `PROMOTION_SERVICE_ENABLED:false`, which short-circuits at `:51-53`; same for tax at `application.yml:56`/`TaxServiceClient.java:36-38`).

**Missed factors:** mitigating — window is bounded (10s open + 5 half-open probes, `application-shared.yml:202-204`), so the breaker *flaps* under ongoing sellout rather than latching; minimum 20 calls gives small-scale shops headroom. Aggravating — reserve is the **first** call of every checkout, so the failure is total for the order path; and because the saga rewrites the cause into ORD-4007 (409), clients see a non-retryable-looking business error for an infrastructure outage.

**Runtime repro to keep in the regression pack:** (1) set stock of product P to 0 via admin API; (2) fire ≥20 sequential checkouts (different users/carts) that each attempt to reserve P — observe 20× 409 ORD-4007; (3) check `resilience4j_circuitbreaker_state{name="inventoryService",state="open"}==1`; (4) checkout a well-stocked product Q → expect fail-fast 409 with breaker text; (5) after the fix (ignoreExceptions), repeat and assert Q succeeds and the breaker stays CLOSED. This is exactly the orchestrator's Black Friday scenario and is deterministic.

---

## 2. ORD-1 (P0) — User cancels a PENDING order after capture: money kept, no refund

**Claim.** Capture is asynchronous relative to order state; in the `PENDING`+`CAPTURED` window a user cancel succeeds with no payment lookup and no refund, and the late captured event is deliberately skipped — money kept, order cancelled, nothing reconciles it later.

**Verdict: CONFIRMED** (every element re-read; also CONFIRMED that no scheduler eventually refunds).

**Evidence I personally read:**
- `OrderServiceImpl.java:187-189` — non-admin cancel requires `status == PENDING` (so the window is exactly "captured event produced, not yet consumed").
- `:192-205` — the PENDING branch releases stock reservations and the promotion reservation and **never contacts payment-service** (no `findCapturedByOrderId`, no `refundByOrderId`).
- `:221-225` — refund exists **only** in the `CONFIRMED && isAdmin` branch; `:230-232` flips to CANCELLED in a `REQUIRES_NEW` tx and publishes.
- `PaymentLifecycleConsumer.java:30-42` — captured event → `confirmOrder`; the consumer **swallows exactly** `ORDER_INVALID_STATE_TRANSITION` (`:36-39`) — the `CANCELLED→CONFIRMED` rejection — and proceeds as if nothing happened. (Other `BusinessException`s are rethrown to DLT, `:40` — but invalid-state is the one that hits here.)
- `OrderEventPublisherImpl.java:72-78` — `order.cancelled.v1` hardcodes `"refunded", false` (`:76`) even when a refund later succeeds — observability blind spot confirmed.
- **Reconciliation sweep (my independent check):** `OrderReconciliationScheduler.java:83-85` scans **PENDING only**; `autoCancel` (`:163-175`) contains no payment lookup and no refund; the file has no payment-service interaction except reservation state polls (`:131-147`). payment-service: grep for `@KafkaListener` → **zero** (it cannot react to `order.cancelled.v1`); its schedulers are outbox relay/retention and webhook retry only (`PaymentOutboxRelay.java:61,196`, `OutboxRetentionScheduler.java:39`, `WebhookRetryScheduler.java:44`) — **no PSP reconciliation of PENDING/captured payments**. The `FailedCompensation` sweeper only retries reservation-kind compensations. So nothing ever refunds a captured payment on a cancelled order.

**Mitigating factor the finding under-weights:** the loss window is small in wall-clock terms (outbox relay poll 5s + consumer lag) and requires the customer's cancel to land inside it; but Stripe/webhook-driven captures plus human "cancel" clicks make it a realistic race, and payment-service's own webhook path (Stripe `payment_intent.succeeded`) can also land while the user is cancelling. Severity P0 (direct financial loss per occurrence, silent) stands.

**NEEDS_RUNTIME_VERIFICATION (deterministic repro):** (1) create a cart+order (`POST /api/v1/orders`) → PENDING; (2) pause the `order-service-payment-group` consumer (`kafka-consumer-groups --pause` or stop order-service's listener container) to widen the window deterministically; (3) create+capture payment via `POST /api/v1/payments` (+capture) → `payment.captured.v1` in the outbox; (4) `POST /api/v1/orders/{id}/cancel` as the owning user → expect 200 CANCELLED; (5) resume consumer → captured event consumed → confirm rejected (log "already transitioned ... skipping"); (6) assert: order CANCELLED, payment CAPTURED, zero rows in `payment_refunds`, `order.cancelled.v1` with `refunded:false`. Post-fix assertion: step 4 either refuses with "await confirmation" or triggers exactly one refund.

---

## 3. ORD-2 (claimed P0) — Duplicate orders on concurrent identical checkouts

**Claim.** With no `Idempotency-Key`, concurrent identical checkouts both pass the cart lock (released at end of TX1, before items are deleted in TX2) and commit two live orders, doubled stock holds and coupon usage.

**Verdict: PARTIALLY_CONFIRMED** — the interleaving is real and reachable **from source**, but the severity label P0 as written is overstated on current live evidence; the demonstrated damage is ghost zero-total orders, not duplicate paid orders. The orchestrator's live A3 result (1 completed order, 4 ghosts, 4 orphan reservations) does **not** refute the interleaving — stock exhaustion masked it.

**Evidence I personally read:**
- `OrderController.java:38` — `@RequestHeader(value = "Idempotency-Key", required = false)`; `IdempotencyServiceImpl.begin:50` — `if (key == null) return Optional.empty();` → no dedup without a client key.
- `OrderCreateSaga.java:121-146` — TX1: cart `FOR UPDATE` (`CartRepository.java:23-31`, `PESSIMISTIC_WRITE`, 3s lock timeout, filter `deleted = false`), items read, **zero-total order INSERT committed** (lock released here); `:152-209` — pricing + per-item inventory reserve run **outside any transaction**; `:211-218` — TX2 saves the priced order, saves items, `cartItemRepository.deleteAll(items)`, soft-deletes the cart.
- `Cart.java:16-32` — **no `@Version`** (extends `AbstractMappedEntity`, which carries only `updatedAt` — I grepped `@Version` in common-core: none). Therefore the loser's TX2 `cartRepository.save(cart)` (a merge) **cannot fail on a version conflict**; both TX2s can commit.
- Orders have no natural key: `Order.java:76` `trackingCode` is a random UUID; no unique constraint on user/cart content (I read the entity; no DB-side dedupe was found cited anywhere else).
- **Ghost-order mechanism (new, resolves the live evidence):** the saga's catch blocks cover **only pricing** (`:153-158`) and the reserve loop (`:191-209`), which delete the TX1 order on failure. Anything failing in `:160-180` or in **TX2 itself (`:211-218`) leaves the TX1-committed zero-total order permanently PENDING with orphan reservations** — precisely the "4 ghost PENDING orders (total=0.00, no line items) + 4 orphan PENDING reservations" the live run observed. Under 20 racers, TX2's `cartRepository.save(cart)` UPDATE contends with later racers' TX1 `FOR UPDATE` row locks → lock-timeout exceptions inside TX2 → 409-ish failures → ghosts. (`OrderReconciliationScheduler.java:107-111` auto-cancels no-item orders after 30 min; reservation TTL 15 min — residue self-heals, as the orchestrator noted.) Pinpointing which exact statement threw per ghost NEEDS_RUNTIME_VERIFICATION (needs order-service logs from the run).

**Why the live test did not produce duplicate paid orders:** with stock for ~1 order, racers die in the reserve loop (`STOCK_INSUFFICIENT` → ORD-4007) and their orders are deleted (`:201`) — the stock guard converted the race into ghosts. The duplicate-live-orders outcome requires **both** racers to pass reserve (sufficient stock) **and** both to pass TX2 (no cart version guard ⇒ possible). So: interleaving CONFIRMED_FROM_SOURCE; live duplicate creation NEEDS_RUNTIME_VERIFICATION with headroom.

**Multi-instance question (explicitly asked):** it does **not** change the conclusion. The serialization point is the **DB row lock** on the cart row (single Postgres), identical for N replicas; the TX1/TX2 split and the missing cart version are instance-agnostic. Multi-instance multiplies racing traffic (more chances the window is hit) but adds no new mechanism and removes none.

**Correction of severity:** keep a P1-class defect: (a) duplicate live orders double-charge/double-hold **when stock is ample** — plausible in normal catalog conditions, not just edge cases; (b) the demonstrated ghost orders are user-visible garbage and strand stock up to 15 min. Fix direction (server-generated idempotency key, or hold the cart lock / atomically claim the cart through TX2, and compensate TX2 failures) is correct in the finding.

**Repro to settle the residual:** 20 concurrent `POST /api/v1/orders` on one cart with stock=1000 and qty=1 → assert whether >1 order with line items results (current source says possible); repeat with stock=1 to reproduce the observed ghost pattern.

---

## 4. ORD-6 (P1) — Remote commits before the guarded status flip; lost races strand committed state

**Claim.** Cancel runs stock/coupon/refund compensations **before** the `@Version`-guarded flip; confirm runs `commitForConfirm` **before** its flip; a lost optimistic-lock race leaves committed inventory on a CANCELLED order (or released stock/refund on a CONFIRMED one) with no compensation and no reconciliation.

**Verdict: CONFIRMED** (code facts; interleavings HIGH_CONFIDENCE_INFERENCE as filed).

**Evidence I personally read:**
- `OrderServiceImpl.cancelOrder`: remote effects at `:192-228` (release, promotion release, admin refund) strictly precede the flip at `:230-232`; `applyCancelledStatus` (`:235-242`) does a plain `save()` of the `Order.java:89-92`-versioned entity — read-then-act, merge-time version check only.
- `OrderServiceImpl.confirmOrder`: `commitCoordinator.commitForConfirm(order, items)` at `:303`; the status flip tx at `:309-317` **after** it; any non-BusinessException afterward → `CONFIRM_COMMIT_FAILED` (`:304-306`, `:322-329`) with no rollback of remote commits.
- `OrderCommitCoordinator.java:46-128` — compensates (`releaseCommitted`, `:130-135`) **only** when a failure occurs inside the coordinator (`:121-127`); on SUCCESS it returns and the caller's flip failure is invisible to it.
- `OrderReconciliationScheduler.java:83-85` — scans `PENDING` only, so a CANCELLED order with COMMITTED reservations is never reconciled (and autoConfirm/autoCancel paths never touch payment).
- Confirm-side damage chain I traced: confirm commits reservations remotely (t1) → user/admin cancel that read the order as PENDING (t0) attempts `release` at t2 — inventory `release` is PENDING→RELEASED only, so it fails against COMMITTED and is merely recorded/logged (`releaseAllReservationsById:251-260` catches and records) → cancel's flip wins at t3 (confirm hasn't flipped) → confirm's flip fails on `@Version` at t4. End state: CANCELLED order, COMMITTED inventory + COMMITTED coupon, captured payment, no refund, no scheduler covers it. The reverse order (cancel wins the release first) is **safe**: confirm's commit then fails inside the coordinator and compensates properly (`:121-127`) — a mitigating factor the finding could state more precisely.

**Missed aggravator:** in the cancel-loses/confirm-wins direction with the **admin** branch, the refund (`:221-225`) has already been triggered when the flip fails — refund against a CONFIRMED, shippable order; and the release failures in the cancel-wins case are recorded with kind `KIND_RESERVATION_RELEASE`, whose sweeper retries the same wrong operation (release vs release-committed) — ledger exists but cannot repair. Fix direction in the finding (atomic conditional flip first, then remote effects, ledger-backed) is the right shape.

---

## 5. ORD-7 (P1) — Tax integration broken (wrong URL + null required fields + disabled by default)

**Claim.** (1) order-service calls `POST /api/v1/backoffice/tax-rates/calculate`, which does not exist on tax-service; (2) the payload sends `taxClassId=null, country=null` against `@NotNull`; (3) tax is disabled by default so every order gets tax 0; hence an admin cannot enable working tax by configuration.

**Verdict: CONFIRMED — all three sub-claims.**

**Evidence I personally read:**
- `TaxServiceClient.java:41` — hardcoded URI literal `/api/v1/backoffice/tax-rates/calculate` (not configurable).
- `utils/common-core/.../ApiPaths.java:45` — `TAX_CALCULATION = API_V1 + "/tax/calculate"`; `tax-service/.../TaxCalculationController.java:24-30` — the only calculate handler, at `ApiPaths.TAX_CALCULATION`, guarded `SERVICE|ADMIN` + `requireCaller("order-service")`.
- `tax-service/.../BackofficeTaxRateController.java:26-61` — `@RequestMapping(BACKOFFICE_TAX_RATES)` exposes GET/GET{id}/POST/PUT{id}/DELETE{id} only; **no `/calculate`** subpath; tax-service has exactly three controllers (glob) — so the client's path 404s. 404 → `HttpClientErrorException` → `ORDER_TAX_CALCULATION_FAILED` (`TaxServiceClient.java:49-51`).
- `PricingServiceImpl.java:130-134` — `new TaxCalculateRequest(null, null, null, taxableAmount, currency)`; `tax-service/.../dto/request/TaxCalculateRequest.java:10-16` — `@NotNull UUID taxClassId`, `@NotNull @Pattern(^[A-Z]{2}$) String country` → even at the correct URL a 400.
- `order-service/src/main/resources/application.yml:53-56` — `enabled: ${TAX_SERVICE_ENABLED:false}` → `TaxServiceClient.calculate` short-circuits to zero (`:36-38`).
- **"Can an admin enable working tax?" — NO.** The URL is a compile-time literal (`TaxServiceClient.java:41`) and the nulls are compile-time (`PricingServiceImpl.java:133`). No env/profile change can produce a working call; enabling the flag guarantees 100% checkout failure (`PricingServiceImpl.calculateTaxSafely` rethrows after releasing the promotion reservation `:135-145`, saga aborts and deletes the order `OrderCreateSaga.java:153-158`).

**Minor correction:** the finding's "documented production posture cf. PaymentGateValidator" is rhetoric, not evidence — I did not verify any doc requiring tax on; the compliance framing (VAT never charged) stands on its own. Severity P1 agreed.

---

## 6. ORD-15 (P1) — Cancelled orders still ship; delivery silently dropped

**Claim.** On cancel, shipping only cancels `CREATED` shipments; an in-flight shipment keeps moving, auto-delivers after 7 days, and the delivered event is a no-op for the cancelled order.

**Verdict: CONFIRMED.**

**Evidence I personally read:**
- `shipping-service/.../kafka/OrderEventConsumer.java:20-25` consumes `shop.order.lifecycle.v1`; `ShipmentServiceImpl.handleOrderEvent:62-69` routes `CANCELLED` → `cancelShipment`.
- `ShipmentServiceImpl.cancelShipment:86-96` — cancels only `status == CREATED`; anything else is logged ("leaving untouched") and skipped — verified verbatim at `:88-91`.
- `ReconciliationScheduler.java:45-64` — hourly cron auto-delivers `findStaleAcknowledgedShipments(IN_FLIGHT, now-7d)`; `ShipmentStatus.java:6-8` — `inFlight = PICKED_UP|IN_TRANSIT|OUT_FOR_DELIVERY`. The scheduler queries shipments only — there is **no order-state check anywhere** in shipping-service (Shipment entity carries no cancelled flag; no order lookup).
- `order-service/.../ShippingDeliveredHandler.java:36-39` — delivered event is a **no-op unless order status is exactly SHIPPED** — a delivered-then-cancelled order pair is dropped, not dead-lettered, not surfaced.

**Aggravators/mitigators:** cancel of a `CREATED` shipment works (safe if the admin cancels before carrier pickup) — the loss window starts at pickup. The 7-day auto-deliver (`application` default `shop.shipping.auto-deliver-days:7`) guarantees the terminal absurdity (goods delivered against a refunded/never-charged cancelled order) without any operator action. Combined with ORD-1 (never charged) or a shipped-order admin cancel + return refund, this is goods loss. CONFIRMED P1.

---

## 7. PRD-1 (P1) — Product detached-merge lost updates

**Claim.** `Product` has no `@Version`; `update` reads the entity outside the transaction and merges a stale full-row snapshot inside a later transaction, silently reverting concurrent writes (ratings, media clears, admin-vs-admin edits including price), and broadcasts the reverted row to Kafka.

**Verdict: CONFIRMED.**

**Evidence I personally read:**
- grep `@Version` over `product-service/src/main/java` → **zero matches** (no optimistic locking on `Product`/`ProductVariant`).
- `ProductServiceImpl.update:146-179` — `repo.findById(id)` at `:149` runs in the repository's own transaction (the method is not `@Transactional`; only `@CachePut` at `:147`), so `existing` is **detached**; the inner `transactionTemplate.execute` (`:160-177`) mutates and `repo.save(existing)` (`:172`) merges **every column** of the stale snapshot. No version check is possible (none exists).
- Concurrent writer verified: `ProductRatingService.apply:19-31` — full read-modify-write of `Product` (sets avgRating/ratingCount on the loaded entity, saves whole row), plus `productEventPublisher.publishUpdated(saved)` (`:30`) — the reverted/overwritten snapshot is republished to `shop.product.lifecycle.v1`, re-aging the search index (consumer applies whatever snapshot arrives).
- Admin-vs-admin price regression: same merge path; no detection. The rating-vs-admin interleaving in the finding is exactly what the code permits (rating event applied between `:149` and the merge commit).

**Mitigating factor:** the update path is `@CachePut`-annotated and admin-facing (ADMIN authority), so frequency is bounded by admin edit rate, not customer traffic; but rating events are constant, and every admin edit races them. P1 agreed (silent data corruption with downstream propagation).

---

## 8. PRD-2 (P1) — Checkout prices from a 10-minute Redis cache with no invalidation

**Claim.** order-service caches product snapshots under `productPrice` for 10 minutes with no invalidation hooks, so checkout prices can be 10 minutes stale in both directions.

**Verdict: CONFIRMED** (and I flag a cross-report inconsistency: the same defect is P2 as ORD-10 in order-saga.md but P1 as PRD-2 in inventory-promo.md — one of the two reports should be corrected).

**Evidence I personally read:**
- `ProductServiceClient.java:28-30` — `@CircuitBreaker(name="productService")` + `@Cacheable(value="productPrice", key="#productId.toString() + ':v2'")` on the checkout pricing fetch.
- `order-service/.../config/CacheConfig.java:21,31` — `PRODUCT_PRICE_TTL = Duration.ofMinutes(10)`, applied to the `productPrice` cache (and `.transactionAware()` at `:32`).
- Invalidation hooks: grep over `order-service/src/main` for `CacheEvict` and `shop.product.lifecycle` → **zero matches**; order-service's only Kafka consumers are `PaymentLifecycleConsumer` and `ShippingDeliveredConsumer` (package glob) — nothing consumes product events. product-service's own `@CachePut`/evictions (`ProductServiceImpl.update:147,173-174`, `delete:192-196`) touch only its own caches, never order-service's.
- Checkout pricing consumes the snapshots: `PricingServiceImpl.java:57-97`.

**Mitigating factors (why P2 is defensible):** the staleness window is hard-bounded at 10 minutes; currency flips fail closed mid-window (`:92-94` `ORDER_PRICING_CURRENCY_MISMATCH`); cache misses during outage fall to the breaker/fail path rather than inventing prices; cart-stored prices are correctly ignored. Aggravating: overcharge and undercharge are both customer-visible money errors. Verdict: real defect, severity between the two filed labels — I'd accept P1 for launch only if price-change frequency is business-critical; the fix (lifecycle-event eviction or version-keyed entries) is cheap.

---

## 9. INV-1 (P2 verify) — Keyless reserve + non-unique order_id → duplicate holds

**Claim.** `POST /inventory/{productId}/reserve` works without an idempotency key; `reservations.order_id` is non-unique and never consulted, so same-order retries multiply holds.

**Verdict: CONFIRMED (server-side gap); trigger is latent for the only in-repo caller — P2 appropriate.**

**Evidence I personally read:**
- `InventoryController.java:83-101` — `Idempotency-Key` header `required = false`; keyless requests go to `reserveWithRetry(productId, request)` (`:94`).
- `InventoryServiceImpl.reserve(productId, request, idempotencyKey):136-168` — null key skips the replay lookup (`:140-145`), inserts a fresh PENDING reservation storing `orderId` (`:153`) but **never checks it** against existing PENDING/COMMITTED reservations.
- Schema: `changelog-001-initial-schema.yaml:53` — `order_id` plain UUID column; `changelog-006-reservations-order-id-index.yaml:8-10` — `idx_reservations_order_id`, a **non-unique** index (the changelog file name itself is the only uniqueness-adjacent artifact; there is no `unique: true` on it).
- Caller side: `OrderCreateSaga.java:185-187` → `StockReservationServiceImpl.reserve:18-20` → `InventoryServiceClient.reserve:42-47` — the request sets **no idempotency header** and order-service has **no retry** around the reserve loop (failure → compensate + abort checkout, `OrderCreateSaga.java:191-209`). So today's only caller cannot spontaneously generate same-order duplicates; the exposure is any future retry/timeout-replay path or an additional SERVICE caller. The finding's HIGH_CONFIDENCE_INFERENCE for the trigger is honest; keep P2.

---

## 10. ARCH-1 (P1) — k8s gateway trusted-proxy misconfiguration collapses per-client rate limiting

**Claim.** Production k8s gateway runs with `trusted-proxy.cidrs` empty and `hops=0`; behind the nginx ingress every external request is metered by the ingress pod IP — per-client buckets collapse to one shared bucket, and the startup validator only warns.

**Verdict: CONFIRMED.**

**Evidence I personally read:**
- `gateway-service/src/main/resources/application.yml:65-67` — `cidrs: ${GATEWAY_TRUSTED_PROXY_CIDRS:}` (empty default), `hops: ${GATEWAY_TRUSTED_PROXY_HOPS:0}`.
- `k8s/gateway-service.yaml:32-42` — Deployment env is exactly `SPRING_PROFILES_ACTIVE=prod`, `JWT_ISSUER_URI`, `SPRING_DATA_REDIS_HOST/PORT`, `CORS_ALLOWED_ORIGIN_PATTERNS` — **no** trusted-proxy or admin-allowlist variables; repo-wide grep over `k8s/` for `TRUSTED_PROXY|ADMIN_IP|GATEWAY_` → zero matches.
- `k8s/ingress.yaml:7` — `kubernetes.io/ingress.class: nginx` routing `/` → `gateway-service:8080` (`:21-25`).
- `gateway-service/.../filter/ClientIpResolver.java:23-32` — returns the transport peer unless `trustedProxyPolicy.trustsForwardedHeaders()` and the peer is trusted.
- `utils/common-spring/.../TrustedProxyPolicy.java:35-36,49-50` — `of(cidrs, hops)` returns an **empty policy** when `cidrs` empty or `hops <= 0`; `trustsForwardedHeaders() = hops > 0 && !trustedNetworks.isEmpty()` — so with the shipped defaults, XFF is ignored and the client IP is the ingress pod IP for every consumer of `resolve` (pre-auth limiter, edge/fleet limiters, admin allowlist).
- `GatewaySecurityPostureValidator.java:94-101` — the `hops==0` case produces exactly `log.warn(... "all clients share the address of the proxy.")` and continues; only the inverse misconfig (hops>0 with empty cidrs, `:87-93`) throws.

**Nuances/corrections:** the admin IP allowlist is disabled by its **own** defaults (`ADMIN_IP_ALLOWLIST` empty + `required=false`, `application.yml:101-103`) — that is a second, independent posture gap, not a consequence of hops=0; conversely, configuring an allowlist while keeping hops=0 would allowlist the ingress IP for all users (worse). Also note the fix prerequisite the finding states correctly: nginx ingress must **overwrite** (not append) XFF for `hops=1` to be sound. Severity P1 agreed (trivially triggerable global 429s).

---

## 11. ARCH-2 (P1) — Versionless `resilience4j-bulkhead` / split classpath

**Claim.** gateway declares `resilience4j-bulkhead` without a version and no BOM manages it; the last packaged jar shows circuitbreaker/micrometer 2.4.0 against core/bulkhead/retry/ratelimiter/timelimiter 2.3.0.

**Verdict: CONFIRMED** (pom facts + jar contents verified by me; what the *current* pom resolves to on a clean build remains NEEDS_RUNTIME_VERIFICATION — as the finding itself marks).

**Evidence I personally read:**
- `gateway-service/pom.xml:56-59` — `io.github.resilience4j:resilience4j-bulkhead` with **no** `<version>`; contrast `:52-55` (circuitbreaker) and `:60-64` (micrometer), which carry `${resilience4j.version}`.
- `pom.xml:48` — `resilience4j.version=2.4.0`; `pom.xml:157-171` — dependencyManagement manages only `resilience4j-spring-boot4`, `resilience4j-reactor`, `resilience4j-micrometer` — **not** bulkhead/core, and no BOM import.
- I listed the jar myself: `gateway-service/target/gateway-service-1.0-SNAPSHOT.jar` `BOOT-INF/lib/` contains `resilience4j-circuitbreaker-2.4.0`, `resilience4j-micrometer-2.4.0`, and `resilience4j-core-2.3.0`, `resilience4j-bulkhead-2.3.0`, `resilience4j-retry-2.3.0`, `resilience4j-ratelimiter-2.3.0`, `resilience4j-timelimiter-2.3.0` — the split fleet exactly as claimed (stale artifact, but the split it documents is real evidence of past unmanaged resolution).
- Gateway's breaker/bulkhead usage: `DownstreamResilienceFilter.java` uses `CircuitBreaker`/`CircuitBreakerRegistry` + bulkhead — the feature is load-bearing (`application.yml:89-100` enables both).

**Runtime check to close it (does not require me):** `./mvnw -pl gateway-service dependency:tree -Dincludes=io.github.resilience4j` on a clean machine; then rebuild and re-list `BOOT-INF/lib`. Fix (import `resilience4j-bom` in root dependencyManagement or pin every artifact) agreed.

---

## 12. QA-1 (P1) — inventory-service `*IT` classes silently dead under plain `mvn test`

**Claim.** inventory-service's pom has no surefire configuration, so its `*IT` tests (matching none of surefire's default includes) never run under plain `mvn test`; the root failsafe `it` profile never activates.

**Verdict: CONFIRMED** — and I extend it: **three** inventory `*IT` classes are dead, not one.

**Evidence I personally read:**
- `inventory-service/pom.xml:128-135` — build plugins: `spring-boot-maven-plugin` **only**; no surefire, no failsafe.
- Root `pom.xml:278-287` — surefire in pluginManagement sets only `user.timezone`; default includes apply (`Test*`, `*Test`, `*Tests`, `*TestCase` — no `*IT`).
- Sibling contrast (the de-facto mechanism): 11 service poms add `<include>**/*IT.java</include>` to surefire — I read `order-service/pom.xml:145-157` (include at `:154`) and grepped the same block into media/gateway/product/search/promotion/notification/tax/rating/payment/shipping poms. inventory-service is the **only** service without it.
- Dead failsafe profile: root `pom.xml:486-550` — profile `it` (failsafe with `**/*IT.java` at `:540-542`) activates only if `src/it/java` exists (`:489-493`); glob over `*/src/it/java/**` → **no module has it**, so failsafe is bound nowhere.
- Affected classes (glob of `inventory-service/src/test/java/**`): `repository/ReservationsOrderIdIndexIT.java`, `service/InventoryOutboxAggregateOrderIT.java`, `service/InventoryOutboxRelayRecoveryIT.java` — none matches surefire defaults; CI runs plain `./mvnw -T1C test` (`.github/workflows/ci.yml:34`), so all three are skipped in CI too.
- Corroborating history: `inventory-service/target/surefire-reports/` contains `TEST-...ReservationsOrderIdIndexIT.xml` etc. — they ran at some point via an explicit `-Dtest` selector (the harness pattern), not via the pom; consistent with, and not contradictory to, the dead-under-default-claim.

**Exact command that runs them:**
`./mvnw -pl inventory-service -Dtest='ReservationsOrderIdIndexIT,InventoryOutboxAggregateOrderIT,InventoryOutboxRelayRecoveryIT' test`
(or `-Dtest='*IT'`; `-Dtest` overrides surefire includes). The structural fix is to copy the sibling surefire `<includes>` block into `inventory-service/pom.xml`. These are Testcontainers tests — Docker required.

---

## 13. KFK-6 (P2 verify) — All SMTP notifications go to one fallback mailbox

**Claim.** Order event payloads carry no recipient email; `resolveRecipient` always falls through to `SMTP_FALLBACK_RECIPIENT`, so customers are never emailed and all order data lands in one mailbox.

**Verdict: CONFIRMED.**

**Evidence I personally read:**
- `SmtpNotificationSender.java:65-85` — recipient resolution looks for exactly `recipientEmail` or `email` keys in the payload JSON; otherwise `:84` returns `fallbackRecipient`. Fail-fast when unset while enabled (`:31-35`).
- `OrderEventPublisherImpl.publishCreated:38-53` — payload fields are orderId, userId, status, items, subtotal/tax/discount/total, currency, optional couponCode, createdAt — **no `recipientEmail`, no `email`**; `publishStatusChanged:57-69` and `publishCancelled:72-78` carry even less.
- Channel reality check: the LOG sender is the default; SMTP activates only when `shop.notification.smtp.enabled=true` (`@ConditionalOnProperty`, `SmtpNotificationSender.java:20`) — mitigating (the defect is dormant unless SMTP is turned on, and when it is, the behavior is deterministic).

---

## 14. AUTH-2 (P2 verify) — No lockout on the realm; ROPC enabled on the public client

**Claim.** The imported realm has no brute-force protection and `ecommerce-client` is a public client with direct access grants (ROPC) enabled; the posture check only warns.

**Verdict: CONFIRMED (realm facts verified by me; call-path details from the kafka-messaging report's citations consistent with what I read).**

**Evidence I personally read:**
- `docker/keycloak/import/ecommerce-realm.json` — grep for `bruteForce|failureFactor|passwordPolicy` → **zero matches anywhere** in the import (brute-force detection is Keycloak-off by default and this import does not enable it); `:35-36` — `ecommerce-client` has `"publicClient": true` and `"directAccessGrantsEnabled": true` (ROPC on a public client); the six other clients all correctly set `publicClient:false` + `directAccessGrantsEnabled:false` (`:58-59` through `:123-124`), so the login client is the sole ROPC surface.
- The risk chain (public `/login` → password grant → unlimited credential stuffing with no realm lockout and no gateway rate-limit distinction of it) follows from `application-shared.yml`'s azp allowlisting of `ecommerce-client` and the gateway pre-auth limiter keying (see ARCH-1 — which additionally collapses per-client limiter keys to the ingress IP, compounding AUTH-2 in the k8s deployment; a genuine cross-finding aggravation).

---

## 15. Orchestrator's finding — gateway declares `/search|/ratings|/medias|/carts` public while services say `public-paths: []`

**Claim.** The gateway treats those path families as anonymous; the owning services (search/rating/media) require authentication, producing anonymous 401s (observed live: 3,888 × 401 = exactly the anonymous search share); `/carts` implication questioned.

**Verdict: CONFIRMED for search/ratings/medias; REFUTED for carts** (the carts part of the claim — "order-service enforces auth on carts" — is wrong).

**Evidence I personally read:**
- `gateway-service/src/main/resources/application.yml:56-61` — `/api/v1/search(/…)?`, `/api/v1/ratings(/…)?`, `/api/v1/medias(/…)?` listed in `public-endpoints`; `:62-63` — `/api/v1/carts(/…)?` also listed.
- Service side: `search-service/application.yml:35` — `public-paths: []`; `rating-service/application.yml:32` — `[]`; `media-service/application.yml:43` — `[]` (all read directly). With the standard `BaseSecurityConfig` model (everything non-public requires authentication), anonymous requests passing the gateway hit 401 at the services — matching the live evidence in `evidence/load/load-results.md:30` (1/3 of ramp requests = all anonymous `/search` calls → 401).
- **Carts is consistent by design, not broken:** `order-service/application.yml:42-44` declares `/api/v1/carts/**` and `/api/v1/orders/track` public **at the service too**, and `CartController.java:30-75` has **no** `@PreAuthorize` on `/me`, item CRUD, or clear — guest carts work anonymously via the `X-Guest-Cart-Id` header (only `/merge` requires authentication, `:77-78`). So anonymous carts succeed both sides. The real carts issue is the bare capability-UUID ownership model (order-saga's ORD-22), not an anonymous-401 mismatch. `load-results.md:30`'s parenthetical "order-service enforces auth" on carts is incorrect.

**Impact correction:** the practical defect is search/rating/media discoverability: anonymous catalog browsing of search results, ratings and media resolution 401s — the storefront must authenticate users before any search. Either the services should declare those read paths public (matching the gateway contract) or the gateway should stop advertising them; today the two contract halves disagree and the gateway side lies.

---

## Summary table

| Finding | Filed severity | My verdict | Key correction |
|---|---|---|---|
| ORCH-P0 breaker on business 4xx | P0 | **CONFIRMED** | `paymentService.findCapturedByOrderId` breaker is inert (swallows all); refund path CAN trip; no shipping/rating clients in order-service; extra trip risks: productService 404s, promotionService coupon 4xx |
| ORD-1 cancel vs late capture | P0 | **CONFIRMED** | No reconciler refunds (payment-service has zero Kafka listeners); window is narrow but real; repro steps provided |
| ORD-2 duplicate concurrent checkouts | P0 | **PARTIALLY_CONFIRMED** | Interleaving real from source (no cart @Version, lock dies at TX1); live duplicates not produced because stock exhaustion masked it → ghosts + orphan holds (which I traced to the uncompensated TX2 window); multi-instance changes nothing; P0 overstated → P1 + ghost-residue defect |
| ORD-6 commits before flip | P1 | **CONFIRMED** | Reverse interleaving (cancel wins first) is safe via coordinator compensation; forward interleaving strands committed stock/coupon/money on CANCELLED with no reconciliation |
| ORD-7 tax integration | P1 | **CONFIRMED** | All 3 sub-claims re-read; admin cannot enable working tax (URL+nulls are compile-time) |
| ORD-15 cancelled orders ship | P1 | **CONFIRMED** | Safe window ends at carrier pickup; 7-day auto-deliver guarantees the end-state |
| PRD-1 detached merge | P1 | **CONFIRMED** | No `@Version` anywhere in product-service; rating events are the routine colliding writer |
| PRD-2 10-min price cache | P1 | **CONFIRMED** | No invalidation hooks exist; severity inconsistent across reports (P1 vs ORD-10's P2) — bounded 10-min window argues P2 |
| INV-1 keyless reserve | P2 | **CONFIRMED** | Server-side gap real; only in-repo caller never retries and never sends a key → latent |
| ARCH-1 trusted-proxy hops=0 | P1 | **CONFIRMED** | Admin allowlist is independently off by its own defaults; allowlist+hops=0 would be worse |
| ARCH-2 versionless bulkhead | P1 | **CONFIRMED** | Jar split 2.4.0/2.3.0 re-verified via jar listing; current-pom resolution still NEEDS_RUNTIME_VERIFICATION |
| QA-1 inventory ITs dead | P1 | **CONFIRMED** | Three dead IT classes (not one); 11 sibling poms have the include, inventory doesn't; exact run command: `./mvnw -pl inventory-service -Dtest='*IT' test` |
| KFK-6 fallback mailbox | P2 | **CONFIRMED** | Dormant unless SMTP enabled (LOG is default) |
| AUTH-2 no lockout + ROPC | P2 | **CONFIRMED** | Realm has zero bruteForce/passwordPolicy keys; six sibling clients are correctly closed; compounds with ARCH-1 in k8s |
| Gateway public-paths mismatch | — | **CONFIRMED (search/ratings/medias) + REFUTED (carts)** | Carts is public at order-service too by design (guest carts, CartController has no @PreAuthorize) — the mismatch affects only search/ratings/medias |

**Refuted item:** the carts leg of the gateway public-paths finding (and the same parenthetical in `evidence/load/load-results.md:30`) — order-service does **not** enforce auth on `/api/v1/carts/**`; anonymous guest-cart access is implemented (`CartController.java:30-75` + `order-service/application.yml:42-44`).
