# Business Correctness Assessment — run-20261122-f242158 (independent, source + live)

## Checkout flow (traced + executed live)
Gateway → cart (order-service owns carts) → POST /orders → OrderCreateSaga: TX1 insert PENDING order → remote: product quote (10-min Redis cache — PRD-2/ORD-10 P1), tax compute (BROKEN — ORD-7 P1: wrong URL + null required fields + disabled default ⇒ tax always 0.00), promotion reserve, inventory reserve (guarded atomic UPDATE) → TX2 save items+clear cart+outbox. Payment is service/admin-initiated (no user-facing payment initiation; order-service never creates payments — only queries/refunds).
Live happy path executed: cart→order→payment capture (REAL Stripe, pi_3UeJOE00k8Tlns6n1uunCXGc succeeded, livemode=false)→outbox payment.captured.v1→Kafka→order auto-CONFIRMED→reservation COMMITTED (89/2 accounting exact)→shipment CREATED→SHIPPED→DELIVERED. Evidence: evidence/stripe/e2e-run-log.md.

## Mandatory questions answered
- Concurrent checkout duplicate orders? NOT observed (20-race same cart: 1 order) BUT the duplicate window is real at source (optional key + TX1 lock release; masked live by stock exhaustion) — ORD-2 P1 (QA-downgraded from P0); decisive stock-ample race queued (dupe-rich). CONFIRMED side-effect: 4 ghost PENDING zero-total orders + orphan reservations per losing racer (ORCH-5, user-visible in /orders/me).
- Price change during checkout? YES-STALE: server re-prices from a 10-min product cache (PRD-2 P1, CONFIRMED_FROM_SOURCE) — a price change up to 10 min old is charged; totals are server-computed (client cannot forge amounts — positive control).
- Same discount twice? NO at quota level (live 200-way storm: exactly 5/200 succeed, used_count=5 exact; per-user limit enforced by atomic upsert). BUT coupon reservation commit ignores campaign deactivation mid-flight (PROM-1 P2).
- Insufficient stock valid order? NO — guarded atomic UPDATE held under 30-user race (exactly 5 orders for stock 5; accounting exact; no oversell in any live scenario; INV core design CONFIRMED SAFE).
- Checkout partial without recovery? YES (ORCH-5 ghosts + ORD-11: crash before idempotencyService.complete blocks checkout up to 24h; reconciliation cleans ghosts ≤30 min).
- Stuck nonterminal orders? PENDING orders without payment rely on reservation TTL + OrderReconciliationScheduler (30 min, live-observed auto-cancel of orphans). CONFIRMED/SHIPPED have no sweeper (ORD-14 P2 — delivery requires manual /ship; 7-day auto-deliver fallback).
- Cancelled later confirmed by delayed event? NO for order state (guards + QA-verified ORD-15: cancelled orders ARE blocked from re-confirm but CAN still physically ship — ORD-15 P1).
- Money after cancel? ORD-1 P0: user-cancel of PENDING order whose payment then captures = money kept, no refund, no compensation path (QA CONFIRMED: payment-service has ZERO Kafka listeners; refund exists only in admin-cancel-of-CONFIRMED branch).
- Currency/precision: BigDecimal everywhere, VND zero-decimal handled for Stripe minor units (StripeCurrencyUnits; live: amount_too_small correctly surfaced). PROM-5 P3: VND percent rounding scale-2.

## Refunds (live)
Partial refund via refundReference: single Stripe refund re_3UeJOE00k8Tlns6n1gMSewQD, duplicate call no-op, over-refund rejected PAY-5007. Idempotency CONFIRMED both sides. Return/RMA flow exists (BackofficeOrderReturnController) with review endpoint; ORD paths audited in findings/order-saga.md.

## Verdict
Core inventory/quota engines are CORRECT under real concurrency (live-verified). Checkout saga has confirmed money-safety P0 (ORD-1), duplicate-window P1 (ORD-2) and partial-failure residue (ORCH-5); tax feature is broken-but-silent (ORD-7). None of these are detectable by the green harness — see qa-test-forensics.md.
