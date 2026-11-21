# Prioritized Remediation Roadmap — run-20261122-f242158
(consolidated from all findings; order = release-blocking first; each item: root cause → change → pattern → acceptance criteria → tests → blast radius → dependencies)

## P0 — release-blocking

### R1. Business 4xx must not trip circuit breakers (ORCH-1)
- Root cause: `@CircuitBreaker` wraps client methods that rethrow business domain exceptions (StockReservationFailedException etc.); no ignoreExceptions/recordFailure anywhere; guarded atomic stock rejections are normal business outcomes.
- Change: per-instance breaker config `resilience4j.circuitbreaker.instances.<name>.ignore-exceptions: [...BusinessException, StockReservationFailedException, ...]` (or record only 5xx/timeout/IOException via `recordFailurePredicate`), fleet-wide in common-config defaults + instance overrides for every downstream client (order-service ×6, product, payment, tax, promotion, shipping, search clients).
- Pattern: Circuit Breaker (correct failure classification); Strategy for failure predicates.
- Acceptance: 100 consecutive INV-3002 stock rejections leave breaker CLOSED; real outage (connect refused) opens it.
- Tests: integration test hammering reserve on an empty-SKU product; breaker state metric assertions.
- Blast radius: all services with downstream clients; config-only, low risk.
- Dependencies: none.

### R2. Refund/cancel money-safety (ORD-1, ORD-3, ORD-5)
- Root cause: cancel paths only refund on admin-cancel of CONFIRMED orders; late captured event after user-cancel is skipped; no "≤1 captured payment per order" guard; reconciliation auto-cancel ignores payment state.
- Change: (a) payment-service enforces one-CAPTURED-per-order via partial unique index; (b) order-service cancel branch checks payment state (captured → refund flow instead of plain cancel); (c) PaymentLifecycleConsumer handles captured-after-cancel by triggering refund (Compensation); (d) reconciliation consults payment before auto-cancel.
- Pattern: Saga Compensation + Idempotent Consumer + Reconciliation.
- Acceptance: all combinations (cancel before/during/after capture, duplicate capture, webhook replay) end with money either fully refunded or order CONFIRMED — invariant table in qa-test-forensics matrix.
- Tests: state-machine matrix IT + duplicate-capture race test + reconciliation IT with captured payment.
- Blast radius: order-service + payment-service; needs careful migration of cancel API semantics.
- Dependencies: R3 (idempotency) recommended first.

### R3. Checkout idempotency hardening (ORD-2 residue + ORD-11)
- Root cause: Idempotency-Key optional; saga TX1 commits order row before cart lock; failure leaves ghost orders + orphan reservations; crash before idempotencyService.complete locks checkout 24h.
- Change: require Idempotency-Key on POST /orders (reject without it); acquire cart lock BEFORE TX1 insert; mark-then-complete with shorter TTL + explicit retry path; cancel TX1 order in catch (compensation) instead of leaving PENDING.
- Pattern: Idempotent Consumer/Receiver (idempotency key), Reservation with rollback.
- Acceptance: 20-race same-cart test → exactly 1 order, zero ghost rows (live test script in evidence/load/load_runner.py).
- Tests: parallel checkout IT (multi-thread, real PG); crash-injection test between TX1/TX2.
- Blast radius: order-service checkout API (breaking change for clients without key) — coordinate with frontend.
- Dependencies: none.

## P1 — fix before release

### R4. Tax integration (ORD-7): fix URL to /api/v1/tax/calculate, send required taxClassId/country, enable via config with integration test; otherwise feature-flag OFF visibly.
### R5. Payment capture uniqueness + webhook dedupe by provider event id (ORD-3, ORD-12, ORD-13): partial unique index on captured payments; dedupe downstream by eventId preserved end-to-end; single TX with lock on payment row in webhook path.
### R6. Cancel/ship/deliver lifecycle guards (ORD-15, ORD-6, ORD-14): atomic UPDATE ... WHERE status= for transitions; shipment-cancel covers CONFIRMED/SHIPPED; drop 7-day auto-deliver or gate on real carrier webhook; sweeper for CREATED shipments.
### R7. Product optimistic locking (PRD-1): @Version on Product + targeted update queries (price/stock/rating counters) instead of detached full-row merge.
### R8. Price freshness at checkout (PRD-2, ORD-10): re-quote price from product-service inside checkout (or subscribe to product events to invalidate cache); enforce server-side total recomputation (already done for totals — keep).
### R9. Gateway k8s posture (ARCH-1, ARCH-9): set gateway.trusted-proxy.cidrs/hops in deployment env behind nginx; enable ADMIN_IP_ALLOWLIST; add NetworkPolicy default-deny + explicit service graph.
### R10. Resilience4j dependency hygiene (ARCH-2, ARCH-4): manage resilience4j BOM in root dependencyManagement; remove versionless bulkhead; replace spring-boot-starter-aop 4.0.0-M2 with the GA artifact.
### R11. Test-evidence gates (QA-1/2/3): add surefire *IT includes to inventory/auth/favourite poms; bind jacoco check + OWASP dependency-check; replace harness-based CI suites with plain `./mvnw -T1C test` evidence.
### R12. Incident notifications (ORCH-3): recreate alertmanager with TELEGRAM_BOT_TOKEN env (already valid in .env — container predates it); fix false-positive DatabaseUnavailable/RedisUnavailable rules keyed on optional exporters; add descriptions to alert rules.
### R13. Stripe webhook reachability (ORCH-2): register real public endpoint (or CLI forwarding in staging); add contract test that replays a genuine Stripe-signed delivery; keep dedupe verified.

## P2 — next hardening wave
- INV-1..4 (reserve idempotency key, terminal replay semantics, sweeper phantom events, TTL extension on payment-in-flight), PROM-1 (commit vs deactivation), PRD-3 (remove/refresh dead public quantity), PRD-5 (ES FAILED reconciliation), ARCH-3/5/6 (timeouts, serializer cleanup, Keycloak client timeouts), KFK-1/3/4/9 (poison classification, FAILED→DLT + backlog metrics, DLT replay tooling, managed topics with N partitions RF=3), KFK-6 (recipient resolution on order events), AUTH-2/3/4 (lockout via Keycloak bruteForceProtector, remove changeme defaults, email verification flow), QA-6/7/8 (multi-instance race tests, expiry-vs-commit race, authz-enabled ITs), ORCH-4 (public-path split-brain), ORCH-5 (ghost-order compensation on cart-lock conflict), ORCH-6 (HPA on app tier / KEDA on lag).

## P3 — hygiene
- ORD-9/10/11/14/17/18/19/20/21/22, INV-5..9, PROM-2..5, PRD-4/6/7, ARCH-7/8/10..21, KFK-2/5/7/8/10/11, AUTH-1/5/6, MEDIA-1, QA-5/11/12/14 — see per-area files for details.
