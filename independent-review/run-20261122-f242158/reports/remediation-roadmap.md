# Prioritized Remediation Roadmap — run-20261122-f242158
(consolidated from all findings; order = release-blocking first; each item: root cause → change → pattern → acceptance criteria → tests → blast radius → dependencies)

## P0 — release-blocking

### R1. Business 4xx must not trip circuit breakers (ORCH-1)
- Root cause: `@CircuitBreaker` wraps client methods that rethrow business domain exceptions (StockReservationFailedException etc.); no ignoreExceptions/recordExceptions anywhere (QA repo-wide sweep); guarded atomic stock rejections are NORMAL business outcomes.
- Change: per-instance breaker config `resilience4j.circuitbreaker.instances.<name>.ignore-exceptions: [...StockReservationFailedException, ...]` (or recordFailurePredicate limiting to 5xx/timeout/IOException), fleet-wide in common-config defaults + instance overrides for every downstream client (order-service inventory/product/payment/promotion/tax clients, other services' clients).
- Pattern: Circuit Breaker with correct failure classification (Resilience4j docs: ignoreExceptions/recordExceptions).
- Acceptance: 100 consecutive INV-3002 stock rejections leave breaker CLOSED; connection-refused outage still opens it.
- Tests: integration test hammering reserve on an empty-SKU product with breaker-state metric assertions.
- Blast radius: all services with downstream clients; config-only, low risk.
- Dependencies: none.

### R2. Refund/cancel money-safety (ORD-1, ORD-3, ORD-5)
- Root cause: cancel paths only refund on admin-cancel of CONFIRMED orders; late captured event after user-cancel is skipped (PaymentLifecycleConsumer.java:36-39); no "≤1 captured payment per order" guard; reconciliation auto-cancel ignores payment state (OrderReconciliationScheduler.java:117-121 → ORD-5).
- Change: (a) payment-service enforces one-CAPTURED-per-order via partial unique index; (b) order cancel branch checks payment state (captured → refund flow, not plain cancel); (c) captured-after-cancel triggers compensation refund; (d) reconciliation consults payment before auto-cancel.
- Pattern: Saga Compensation + Idempotent Consumer + Reconciliation.
- Acceptance: invariant table (qa-test-forensics matrix R-rows) — every cancel/capture interleaving ends with money refunded OR order CONFIRMED.
- Tests: state-machine matrix IT + duplicate-capture race + reconciliation IT with captured payment.
- Blast radius: order-service + payment-service; cancel API semantics change.
- Dependencies: R3 recommended first.

### R3. Checkout idempotency + saga compensation (ORD-2 residue, ORCH-5, ORD-11)
- Root cause: Idempotency-Key optional (null-key path IdempotencyServiceImpl.java:50); TX1 commits order before cart lock held through TX2; failures in :160-180/TX2 leave ghost zero-total orders; crash before idempotencyService.complete locks client 24h (ORD-11).
- Change: require Idempotency-Key on POST /orders (or server-derived from cart+user); hold/claim cart atomically through TX2; add catch-all compensating delete of the TX1 order on saga failure.
- Pattern: Idempotent Receiver; Reservation with rollback.
- Acceptance: 20-race same-cart test (stock=1000) → exactly 1 order WITH line items, zero ghost rows; (stock=5) → ≤5 orders, zero ghosts.
- Tests: multi-thread IT on real PG; crash-injection between TX1/TX2.
- Blast radius: order checkout API (breaking for keyless clients — coordinate frontend).
- Dependencies: none.

## P1 — fix before release

### R4. Tax integration (ORD-7): fix URL to /api/v1/tax/calculate (TaxServiceClient.java:41), send required taxClassId/country, add integration test; feature-flag OFF visibly if not fixed.
### R5. Payment capture uniqueness + webhook dedupe (ORD-3, ORD-12, ORD-13): partial unique index captured-per-order; eventId preserved end-to-end; single TX + row lock on webhook payment mutation path.
### R6. Cancel/ship/deliver lifecycle guards (ORD-15, ORD-6, ORD-14): atomic UPDATE...WHERE status= transitions; shipment-cancel covers CREATED/SHIPPED; gate 7-day auto-deliver on carrier truth; sweeper for stale CREATED shipments.
### R7. Product optimistic locking (PRD-1): @Version on Product + targeted UPDATEs (price/rating counters) replacing detached full-row merge (ProductServiceImpl.java:149,160-177).
### R8. Price freshness at checkout (PRD-2, ORD-10): re-quote server-side inside checkout or event-driven invalidation of order-service productPrice cache (CacheConfig.java:21,31, TTL 10min no invalidation).
### R9. Gateway k8s posture (ARCH-1, ARCH-9): set GATEWAY_TRUSTED_PROXY_CIDRS/HOPS + ADMIN_IP_ALLOWLIST in deployment env; NetworkPolicy default-deny with explicit service graph.
### R10. Resilience4j dependency hygiene (ARCH-2, ARCH-4): manage resilience4j via BOM in root dependencyManagement; drop versionless bulkhead; replace spring-boot-starter-aop 4.0.0-M2 (pom.xml:74) with GA.
### R11. Test-evidence gates (QA-1/2/3): add **/*IT.java surefire includes to inventory/auth/favourite poms (pattern exists in 10 other modules); bind jacoco check + OWASP dependency-check; CI suites run via plain `./mvnw -T1C test`, not harness pattern selectors.
### R12. Incident notifications (ORCH-3, ORCH-7): recreate alertmanager container to render telegram receiver (token already valid in .env; bot @ecommerceminhBot verified live); fix DatabaseUnavailable/RedisUnavailable to probe real postgres/redis (not optional exporters); add alert annotations/descriptions.
### R13. Stripe webhook reachability (ORCH-2): register real public endpoint or CLI forwarding in staging; replay contract test with genuine Stripe signature; keep dedupe.
### R14. ORD-16 COD exposure + ORD-4 PSP-in-transaction: provider config allow-list; move PSP calls outside DB transactions.

## P2 — next hardening wave
INV-1..4 (reserve idempotency key, terminal replay semantics, sweeper phantom events, TTL extension on payment-in-flight); PROM-1 (commit vs deactivation); PRD-3 (remove/refresh dead public quantity fields); PRD-5 (ES FAILED reconciliation); ARCH-3/5/6 (timeouts, serializer cleanup, Keycloak client timeouts); ARCH-9 (NetworkPolicy — also listed R9); KFK-1/3/4/9 (poison classification, FAILED→DLT + backlog metrics, DLT replay tooling, managed topics partitions/RF); KFK-6 (recipient resolution on order events); AUTH-2/3/4 (Keycloak brute-force, remove changeme defaults, email verification); QA-6/7/8 (multi-instance race tests, expiry-vs-commit race, authz-enabled ITs); ORCH-4 (public-path split-brain); ORCH-5 (ghost-order compensation); ORCH-6 (HPA/KEDA on app tier, lag-based scaling); KFK-2 (order relay claim single-flight).

## P3 — hygiene
ORD-9/18/19/20/21/22, INV-5..9, PROM-2..5, PRD-4/6/7, ARCH-7/8/10..21, KFK-5/7/8/10/11, AUTH-1/5/6, MEDIA-1, QA-5/11/12/14 — details in per-area findings files.
