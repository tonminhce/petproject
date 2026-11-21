# Independent Load & Concurrency Evidence — run-20261122-f242158

Runner: independent-review/run-20261122-f242158/evidence/load/load_runner.py (stdlib Python, persistent connections, barrier-synchronized races).
Environment: local Docker Compose (single instance per service), 16GB RAM host shared with an EXTERNAL concurrent operator (another session was actively recreating gateway/keycloak containers and running k6 bursts during the audit — see interference notes).

## A. Concurrency races (business invariants)

### A1. Promotion quota storm — quota=5 coupon, 200 concurrent reserves
- Result: **exactly 5 created (201), 195 rejected**. p50=867ms, p95=9.7s (retry/backoff tail on rejected reserves).
- Durable state: campaign used_count=5 = max_redemptions; coupon_usage_reservation = 5 PENDING rows; 0 divergences.
- VERDICT: quota enforcement held (atomic guarded UPDATE path verified live).

### A2. Hot-SKU oversell — stock=5, 30 distinct registered users racing full checkout
- Result: **exactly 5 orders created**, 25 failed ORD-4007. 5 PENDING reservations (1 per order), available=5/reserved=5 consistent, TTL 15 min.
- Users registered through auth-service /api/v1/auth/sign-up + login (also E2E validation of registration).
- VERDICT: no oversell at business level; guarded atomic UPDATE held under full-saga concurrency.

### A3. Same-cart duplicate checkout — 20 concurrent POST /orders on one cart
- Result: **exactly 1 order completed** (1 OK, 4 ERR-0409 cart-lock conflicts, 15 ORD-4007 stock rejects).
- BUT: the 4 ERR-0409 racers left **4 ghost PENDING orders (total=0.00, no line items) + 4 orphan PENDING reservations** — visible via GET /orders/me (CONFIRMED_BY_REPRODUCTION).
- The 5th racer's full saga completed. OrderReconciliationScheduler.java:109-111 auto-cancels orphan no-item orders after `order.reconciliation.stuck-minutes:30`; reservations expire by 15-min TTL — residue self-heals in ≤30 min.
- VERDICT: no duplicate paid orders; confirmed P2 partial-failure residue finding (user-visible ghost orders + stranded stock windows).

### Cross-check vs subagent claims
- order-saga subagent claimed ORD-2 (P0) "concurrent identical checkouts create duplicate orders" — NOT reproduced in this scenario (single instance). The claimed unsafe interleaving (cart lock released before item deletion) did not yield duplicates; QA challenge pending on the multi-instance variant.

## B. Throughput ramp (60s, 12 workers, stable-gateway window)

- Mixed public reads (products list / product detail / anonymous search): **11,664 requests, 194.4 rps, p50=36.3ms, p95=205.2ms, p99=388.0ms, zero 5xx**.
- 3,888 requests (exactly 1/3 = all anonymous /search calls) got **401** — anonymous search is broken despite gateway public-endpoints declaring /api/v1/search/** public (gateway application.yml:56-57) while search-service declares `public-paths: []` (search-service application.yml:35). Same mismatch applies to /ratings/** and /medias/** (rating-service yml:32, media-service yml:43); /api/v1/carts/** is also gateway-public but order-service enforces auth.
- Latency sanity: same products query direct to product-service = 0.01s; via warm gateway = 15-46ms. Earlier 2.5-6s gateway measurements were cold-start churn from the external operator recreating gateway-service every few minutes (docker events: create/kill/destroy/rename/start cycles) — discarded, not a platform defect.

## C. Interference disclosure
- Another process (external to this audit) restarted/recreated keycloak and gateway-service repeatedly (18:41, 19:06-19:14 windows) and ran k6 checkout bursts (idempotency_keys k6-* at 19:02:56-57). One such recreate left gateway-service in "Dead"/"Created" state causing ERR-0503 rate-limiter-unavailable 503s on auth endpoints until recovery. Concurrency-race results above were captured in quiet windows; the ramp p99 may still carry residual interference.

## D. Black Friday classification
- Measured: single-instance dev stack sustains ~194 rps public reads with sub-400ms p99 and correct business invariants under 30-200-way races.
- NOT MEASURED: multi-replica horizontal scaling, Kafka backlog at scale, sustained peak, soak, dependency-failure-under-load at production topology.
- CLASSIFICATION: **INSUFFICIENT EVIDENCE** for Black Friday readiness at production scale (no Kubernetes cluster available; single-node compose only; external interference limits long soak windows).
