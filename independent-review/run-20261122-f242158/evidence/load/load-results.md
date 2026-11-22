# Independent Load & Concurrency Evidence — run-20261122-f242158

Runner: independent-review/run-20261122-f242158/evidence/load/load_runner.py (stdlib Python, persistent connections, barrier-synchronized races).
Environment: local Docker Compose (single instance per service), 16GB RAM host shared with an EXTERNAL concurrent operator (another session actively recreating gateway/keycloak containers and running k6 bursts during the audit — see interference notes).

## A. Concurrency races (business invariants)

### A1. Promotion quota storm — quota=5 coupon, 200 concurrent reserves
- Result: **exactly 5 created (201), 195 rejected**. p50=867ms, p95=9.7s (retry/backoff tail on rejected reserves).
- Durable state: campaign used_count=5 = max_redemptions; coupon_usage_reservation = 5 PENDING rows; 0 divergences.
- VERDICT: quota enforcement held (atomic guarded UPDATE path verified live).

### A2. Hot-SKU oversell — stock=5, 30 distinct registered users racing full checkout
- Result: **exactly 5 orders created**, 25 failed ORD-4007. 5 PENDING reservations (1 per order), available=5/reserved=5 consistent, TTL 15 min.
- Users registered through auth-service /api/v1/auth/sign-up + login (also E2E validation of registration).
- VERDICT: no oversell at business level; guarded atomic UPDATE held under full-saga concurrency.

### A3. Same-cart duplicate checkout — 20 concurrent POST /orders on one cart (stock=5, qty=1)
- Result: **exactly 1 completed order** (1 OK, 4 ERR-0409 cart-lock conflicts, 15 ORD-4007 stock rejects).
- The 4 ERR-0409 racers left **4 ghost PENDING orders (total=0.00, no line items) + 4 orphan PENDING reservations** — visible via GET /orders/me (CONFIRMED_BY_REPRODUCTION).
- OrderReconciliationScheduler.java:107-111 auto-cancels no-item orders after `order.reconciliation.stuck-minutes:30`; reservations expire by 15-min TTL — residue self-heals in ≤30 min.
- QA challenger analysis: stock exhaustion MASKED the duplicate-order interleaving — racers died in the reserve loop (orders deleted at OrderCreateSaga:201). Ghosts come from the UNCOMPENSATED window between TX1 and TX2 (OrderCreateSaga.java:160-218 has no catch for failures at :160-180 or inside TX2 :211-218; cart UPDATE contends with later TX1 FOR UPDATE locks).
- RESIDUAL (queued for post-restore stack): 20 racers on ONE cart with stock=1000 to determine whether >1 order WITH line items can be created (duplicate paid orders).
- VERDICT: no duplicate paid orders observed in this run; confirmed P1/P2-class partial-failure residue + unresolved duplicate-window risk (ORD-2 PARTIALLY_CONFIRMED).

## B. Throughput ramp (60s, 12 workers, stable-gateway window)
- Mixed public reads (products list / product detail / anonymous search): **11,664 requests, 194.4 rps, p50=36.3ms, p95=205.2ms, p99=388.0ms, zero 5xx**.
- 3,888 requests (exactly 1/3 = all anonymous /search calls) got **401** — anonymous search broken despite gateway declaring /api/v1/search/** public (gateway application.yml:56-57) while search-service declares `public-paths: []` (search-service application.yml:35). Same mismatch for /ratings/** and /medias/** (rating yml:32, media yml:43). Carts leg REFUTED by QA: order-service also declares carts public by design (guest carts).
- Latency sanity: products query direct to product-service = 0.01s; via warm gateway = 15-46ms. Earlier 2.5-6s gateway numbers were cold-start churn from the external operator's container recreations — discarded.

## C. Interference disclosure
- External process restarted/recreated keycloak and gateway repeatedly (18:41, 19:06-19:22 UTC), ran k6 bursts (19:02:56-57), and wiped the audit tree twice (02:00, 03:00 local). One gateway recreation left it Dead → ERR-0503 rate-limiter FAIL_CLOSED 503s on auth endpoints until recovery. Concurrency-race results above captured in quiet windows; ramp p99 may carry residual interference.

## D. Circuit breaker live incident
- See breaker-incident.md (P0): business 409s from a sold-out SKU tripped all 5 order-service downstream breakers → platform-wide checkout outage. Directly Black Friday relevant.

## E. Black Friday classification
- Measured: single-instance dev stack sustains ~194 rps public reads (sub-400ms p99) and correct invariants under 30-200-way races.
- NOT MEASURED: multi-replica horizontal scaling, sustained peak, soak, Kafka backlog at scale, dependency-failure-under-load at production topology (no cluster; single-node compose).
- CLASSIFICATION: **INSUFFICIENT EVIDENCE** for Black Friday readiness at production scale — plus a CONFIRMED P0 that would cause checkout outage under the exact hot-SKU-sellout condition Black Friday produces.

## F. DECISIVE ORD-2 race — dupe-rich (post-restore, 2026-11-21T20:31:29Z, stock=1000, qty=1, 20 racers same cart)
- Result: **exactly 1 completed order (OK); 19× ERR-0409; 0 duplicate orders** — even with ample stock the cart lock fully serializes checkout completion. QA upgrade rule applied: ORD-2 stays P1 (duplicate-order upgrade REFUTED empirically).
- Residue confirmed at scale: **19 ghost PENDING zero-total orders + 19 orphan PENDING reservations** (inventory reserved_quantity=20 = 19 losers + 1 winner; available 1000 unchanged). Every racer that loses at the TX2/cart-lock stage leaves the uncompensated pair. Self-heal: reservation TTL 15 min + reconciliation auto-cancel ≤30 min.
- Confidence: CONFIRMED_BY_REPRODUCTION (rate 19/20 losers at TX2 stage under ample stock; 4/20 under scarce stock).
