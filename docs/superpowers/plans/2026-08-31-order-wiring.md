# Order Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire order-service to its two new siblings: payment-CAPTURED confirm guard (flag-gated, fail-closed), `shipping.delivered.v1` consumer driving DELIVERED, payment HTTP surface, gateway webhook exposure.

**Spec:** [`docs/superpowers/specs/2026-08-31-order-wiring-design.md`](../specs/2026-08-31-order-wiring-design.md) — binding.

## Global Constraints

- Single lane, worktree branch `epic/order-wiring`. Touches: `payment-service/*` (controller only), `order-service/*` (client + guard + consumer), `gateway-service/*` (public-paths only, if needed), shared tails (ErrorCode ORD-4012 + i18n), compose (order stanza flag note — enable is an OPS checklist item, NOT committed enabled; follow the tax-flag precedent of what T11 did there — check and mirror).
- **ErrorCode anchor:** `CARRIER_NOT_CONFIGURED("SHP-10006", "shipping.carrier_not_configured", HttpStatus.CONFLICT);` is the last entry → flip `;` to `,`, append `ORDER_PAYMENT_NOT_CAPTURED("ORD-4012", "order.payment.not_captured", HttpStatus.CONFLICT);`. Verify exactly one `;` after.
- Copy sources: `TaxServiceClient` (order, HTTP client precedent incl. error mapping), notification's `NotificationListenerConfig`/`OrderEventConsumer` (consumer stack + `@EnableKafka`), payment's `BackofficePaymentController` + its @WebMvcTest (controller matrix), promotion/tax flag pattern for `shop.services.payment.enabled`.
- Order consumer DTO tolerant (`@JsonIgnoreProperties(ignoreUnknown=true)`): wrapper `{eventId, eventType, occurredAt}` + `orderId, shipmentId, carrier, trackingNumber, autoDelivered`.
- Order FSM untouched (spec D6). Non-SHIPPED source on delivered event → ack no-op.
- `@WebMvcTest` package imports per fleet; no code comments; GitNexus unavailable in worktrees is a known accepted substitute (grep + git diff).

## File Map

| File | Action |
|------|--------|
| `payment-service/.../controller/PaymentController.java` + `CreatePaymentRequest` validation | CREATE/MODIFY (Task 1) |
| `payment-service/.../PaymentControllerTest.java` | CREATE (Task 1) |
| `utils/common-core/.../ErrorCode.java` + `messages_{en,vi}.properties` | MODIFY — ORD-4012 (Task 2) |
| `order-service/.../client/PaymentServiceClient.java` (+ dto) | CREATE (Task 2) |
| `order-service/.../service` confirm path guard + flag | MODIFY (Task 2) |
| `order-service` guard unit tests | CREATE (Task 2) |
| `order-service/.../kafka/ShippingListenerConfig.java`, `ShippingDeliveredConsumer.java`, `.../dto/ShippingDeliveredEvent.java` | CREATE (Task 3) |
| `order-service` consumer unit tests | CREATE (Task 3) |
| `gateway-service` public-paths (verify-first) | MODIFY if needed (Task 4) |
| compose + docs notes (unwrap footgun, CANCELLED matrix, ops checklist) | Task 4 |
| final whole-branch review | Task 5 |

---

### Task 1: PaymentController (spec D2)

**Files:** Create `payment-service/.../controller/PaymentController.java`, `PaymentControllerTest.java`; modify `dto/CreatePaymentRequest.java` (bean-validation annotations: orderId @NotNull, amount @NotNull @DecimalMin("0.01") @Digits(integer=17, fraction=2), currency @NotBlank @Size(min=3,max=3), idempotencyKey @NotBlank @Size(max=64)).

**Interfaces:** `POST ApiPaths? — storefront path: check ApiPaths for a payments base constant (BACKOFFICE_PAYMENTS is admin; if no storefront constant exists use `/api/v1/payments` literal via a new ApiPaths constant `PAYMENTS = API_V1 + "/payments"` — shared file, this lane owns it now)` — create (SERVICE+ADMIN `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")`), capture `POST /{id}/capture` (SERVICE+ADMIN), refund `POST /{id}/refund` (ADMIN per spec), `GET ?orderId=` — extend `BackofficePaymentController` list method's `@PreAuthorize` to `hasAnyRole('SERVICE','ADMIN')` (orderId-filtered path only stays meaningful; unfiltered also allowed for SERVICE — acceptable, judge in review) OR add a dedicated SERVICE endpoint; pick the smaller diff and justify in report.

- [ ] **Step 1 (RED):** @WebMvcTest matrix: anon 401, USER 403, SERVICE 200 create/capture, ADMIN 200 refund + list; validation 400 ERR-0422-V on blank fields; PAY-5002/5004/5006 mapping via service-throw.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement → **Step 4:** PASS + `./mvnw -pl payment-service test` green.
- [ ] **Step 5: Commit** — `feat(payment-service): payment HTTP surface (create/capture/refund + SERVICE read)`

### Task 2: PaymentServiceClient + confirm guard + ORD-4012 (spec D1/D5)

**Files:** Modify `ErrorCode.java`, `messages_{en,vi}.properties`; create `order-service/.../client/PaymentServiceClient.java` + response DTO; modify the confirm service path (locate the actual confirm owner — the confirm-hardening epic's coordinator/service); create guard tests.

**Interfaces:**
- `PaymentServiceClient` — RestClient mirroring `TaxServiceClient`'s shape: `Optional<PaymentStatusSnapshot> findCapturedByOrderId(UUID orderId)` — GET payment-service `/api/v1/payments?orderId=` (SERVICE token — check how TaxServiceClient authenticates and mirror); ANY failure (non-2xx, timeout, malformed) → `empty` (fail-closed upstream) or throws mapped `ORDER_PAYMENT_NOT_CAPTURED`? Mirror TaxServiceClient's exact error posture and note the choice.
- Guard: in the confirm path, when `shop.services.payment.enabled=true`: `findCapturedByOrderId` must yield a CAPTURED payment else throw `BusinessException(ORDER_PAYMENT_NOT_CAPTURED, orderId)`; flag=false → bypass (existing behavior). Flag binding: `@Value("${shop.services.payment.enabled:false}")` or properties class — mirror the tax flag's mechanism EXACTLY (find it first).
- `PaymentStatusSnapshot` record: orderId, status (String), id.

- [ ] **Step 1:** ErrorCode anchor flip + ORD-4012 + i18n (EN `Payment has not been captured for this order`; VI `Thanh toán của đơn hàng chưa được xác thực`). `install -DskipTests` + order compile.
- [ ] **Step 2 (RED):** guard unit tests — flag-off bypass; flag-on + CAPTURED → confirm proceeds; flag-on + PENDING/REFUNDED/absent/client-throws → ORD-4012; flag-on + client-throws never leaks raw exception.
- [ ] **Step 3:** FAIL → **Step 4 (GREEN):** implement → **Step 5:** PASS + `./mvnw -pl order-service test` green.
- [ ] **Step 6: Commit** — `feat(order-service): payment-captured confirm guard (flag-gated, fail-closed) + ORD-4012`

### Task 3: Shipping-delivered consumer (spec D3)

**Files:** Create `order-service/.../kafka/ShippingListenerConfig.java`, `.../kafka/ShippingDeliveredConsumer.java`, `.../dto/ShippingDeliveredEvent.java`, consumer tests.

**Interfaces:**
- `ShippingListenerConfig extends BaseKafkaListenerConfig<String, ShippingDeliveredEvent>` — `@Configuration @EnableKafka`, factory bean `shippingListenerFactory` (notification mirror; group/`auto-offset-reset` via `shop.kafka.consumer.*` — order's yml needs the consumer block added: group `order-service`, `latest`).
- Consumer `@KafkaListener(topics = "shop.shipping.lifecycle.v1", containerFactory = "shippingListenerFactory")` → processMessage → handler service: eventType `shipping.delivered.v1` + order SHIPPED → `validateTransition(SHIPPED, DELIVERED)` + persist + publish `order.updated.v1` via the EXISTING order event publisher (find how confirm/ship publish today and reuse — do not build a new publisher); non-SHIPPED or non-delivered eventType → ack no-op + INFO.
- No event table (spec D3 idempotency note).

- [ ] **Step 1 (RED):** unit tests — SHIPPED→DELIVERED transitions + publishes; already-DELIVERED no-op; CONFIRMED source no-op; non-delivered eventType no-op; handler exceptions contained (never escape to listener).
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement + yml consumer block → **Step 4:** PASS + `./mvnw -pl order-service test` green.
- [ ] **Step 5: Commit** — `feat(order-service): shipping.delivered consumer drives order DELIVERED`

### Task 4: Gateway exposure + compose/docs notes (spec D4/D7)

**Files:** `gateway-service` config (verify-first, minimal change), `docker-compose.yml` (order stanza — add the payment flag env DISABLED by default if the tax flag precedent commits it disabled; otherwise ops-note only), docs note.

- [ ] **Step 1:** Read gateway security/routing config: how ServiceRoute prefixes forward; whether JWT is enforced edge-wide or per-route. Determine the minimal public-paths mechanism for `/payments/api/v1/webhooks/**` + `/shipping/api/v1/webhooks/**` (HMAC stays sole auth, service-side).
- [ ] **Step 2:** Apply minimal change (if none needed — document why). `docker compose config -q` green.
- [ ] **Step 3:** Docs: append the D7 notes (unwrap-once footgun + CANCELLED matrix + ops checklist: enable `SHOP_SERVICES_PAYMENT_ENABLED` post-verify; carrier webhook base URLs via gateway) to the wiring spec's §Ops or a short README note in `docs/superpowers/` — judge placement.
- [ ] **Step 4: Commit** — `feat(gateway): webhook path exposure + wiring ops notes`

### Task 5: Final whole-branch review

- [ ] **Step 1:** review-package BASE→HEAD; reviewer checks: D1 flag semantics + fail-closed posture (client-throw → ORD-4012, never raw), D2 controller roles vs spec table + validation mapping, D3 consumer (@EnableKafka present, ack-always, FSM-only writes, existing publisher reuse), D4 gateway minimalism + HMAC-not-JWT posture, D5 anchor, zero regression outside the 3 services + tails, both module suites green evidence.
- [ ] **Step 2:** fix rounds; ledger close. Ops: enable payment flag → confirm a paid order end-to-end (create payment → mock capture → order confirm) as the post-deploy smoke.
