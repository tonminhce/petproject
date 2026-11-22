# Distributed Consistency Assessment — run-20261122-f242158 (independent)

## Messaging spine (source-verified + live)
- EVERY business publish goes through per-service `outbox_events` written in the same DB transaction as the state change; scheduled relays publish to Kafka. ZERO direct kafkaTemplate.send in business code (only DLT replay). 10 topics match the reported list exactly (kafka-messaging.md §4).
- Claim strategy: `pg_try_advisory_xact_lock` + `FOR UPDATE SKIP LOCKED` + per-aggregate single-flight in 7/8 relay implementations → multi-instance safe (order-service relay uses blocking per-row claim without single-flight — KFK-2 P3 cross-instance out-of-order window).
- Live proof: payment.captured.v1 row SENT 18:52:04 → order auto-confirmed 18:52:16; outbox backlog drains to 0 (order_outbox_pending_count metrics live in Prometheus).
- Failure windows analyzed: DB-commit-before-publish (republish via lease-reclaim → at-least-once, consumers dedupe by state/unique keys — NOT eventId: ORD-12 P2 notes fresh eventId defeats dedupe on webhook replay); publish-before-mark (duplicate event, downstream dedupe covers); consumer-crash-before-ack (redelivery; BATCH ack).

## Delivery semantics reality (no "exactly-once" claims)
- Kafka: at-least-once everywhere. Consumers: state-machine guards (order confirm/cancel are idempotent by status), unique constraints (notification uk_event_id, payment_events provider_event_id), ES external versioning (occurredAt nanos). Duplicate delivery of rating/stock events is benign by design (server-side aggregate snapshot).
- Poison messages: DefaultErrorHandler + DLT (FixedBackOff 3×1s then <topic>-dlt) — BUT search/media consumers ack-SKIP failures (KFK-1 P2, silent data loss) and outbox FAILED is terminal without alert in rating/product/order (KFK-3 P2).
- Ordering: keyed by aggregate id; single-partition topics (auto-created 1p/RF1 — KFK-9 P2) currently serialize per-topic; multi-partition migration would expose KFK-2.

## Saga & compensation
- OrderCreateSaga with remote reserve/commit/release on inventory+promotion (reservation pattern, guarded atomic UPDATEs — live-verified correct under races).
- Compensation exists for pricing/reserve loop failures (order deleted); MISSING for TX2 failures (ORCH-5 ghosts) and for the captured-payment-vs-cancel race (ORD-1/ORD-5 P0/P1 — money kept).
- Reconciliation: order-service (stuck PENDING, 30 min, live-observed), reservation sweeper (15-min TTL, live-observed 67-release), shipment retry, webhook retry scheduler. "Mixed" reconciliation bucket never resolves (ORD-20 P3) but routes to metric+log.
- Idempotency: order-service idempotency_keys table (actor,key) with 24h retention; key OPTIONAL for checkout (ORD-2 window); payment idempotency_key unique + Stripe Idempotency-Key header passthrough (live-verified single intent per key).

## Multi-instance readiness
- Reservation/coupon: DB-atomic — no JVM state in the critical path (verified: no statics in inventory/promotion hot paths; Redis only cache). 2-replica k8s deployment safe for these two.
- Relays/schedulers: advisory-lock claimed → multi-instance safe EXCEPT order relay single-flight gap (KFK-2) and media sweep JVM-local cursor (PRD-6 P3).
- Gateway rate limit: Redis Lua buckets, atomic, multi-instance safe (verified pattern).
- NOT VERIFIED: any behavior at >1 instance of app services in this audit (no cluster; compose single-instance). Source analysis says safe; runtime evidence absent → mark NEEDS_RUNTIME_VERIFICATION.

## Conclusion
The outbox+relay+guarded-UPDATE architecture is genuinely and correctly implemented (rare to see done this cleanly); consistency defects are at the BOUNDARIES: missing compensation for TX2/cancel-vs-capture (money), terminal FAILED events without alerting (silent drift), ack-skipped poison paths (search drift), and unmanaged topics. None threatens stock/coupon correctness; two threaten money.
