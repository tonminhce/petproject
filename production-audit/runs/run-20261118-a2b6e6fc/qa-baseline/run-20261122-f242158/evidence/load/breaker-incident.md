# CONFIRMED P0 — Business 4xx rejections trip checkout circuit breaker (live incident)

## Timeline (2026-11-21 UTC)
- 19:02:56-57: external k6 load reserves ALL sellable stock of product 9f5166c0 (idempotency_keys k6-* rows).
- 19:05-19:12: my race tests succeed (orders created; breakers closed or not yet tripped).
- ~19:14-19:22: k6/prior sellout traffic accumulates 409 "Insufficient stock" (INV-3002) responses on inventory-service.
- 19:20-19:22: Prometheus shows resilience4j_circuitbreaker_state{state="open"}=1 for inventoryService, productService, paymentService, ratingService, taxService (all in order-service).
- 19:22:23: POST /orders for an in-stock product (2b4d8777) → HTTP 409 ORD-4007
  message: "Failed to reserve stock for product CircuitBreaker 'inventoryService' is OPEN and does not permit further calls" (traceId 1045ff7f489ce40916ae83e2431f65f8).
- 19:22:39: inventory-service AUDIT log proves it is HEALTHY and responding normally:
  actor=order-service action=inventory.reserve outcome=fail code=INV-3002 "Insufficient stock for the requested quantity" (business 409, sub-second).

## Root cause (source)
- order-service/src/main/java/com/shop/orderservice/client/InventoryServiceClient.java:39 — `@CircuitBreaker(name = "inventoryService")` wraps `reserve(...)`.
- The method catches HttpClientErrorException (409) and rethrows `StockReservationFailedException` (order-service/.../exception/StockReservationFailedException.java:8 — plain RuntimeException).
- Resilience4j records ANY exception thrown out of the annotated method as a failure unless ignoreExceptions is configured. NO ignoreExceptions/recordFailure exists anywhere (grep across order-service resources and utils/common-config application-shared.yml:194-216 — only the generic default config: failure-rate 50%, COUNT_BASED window 100, minimum calls 20).
- Same unprotected pattern on ProductServiceClient.java:28, PaymentServiceClient.java:61,67,125, tax/rating clients.

## Failure chain (Black Friday scenario)
1. Flash-sale SKU sells out → every reserve for it returns 409 INV-3002 → StockReservationFailedException.
2. Failure rate crosses 50% of the 100-call window (minimum 20 calls) → breaker OPEN.
3. ALL checkouts now fail fast for ALL products: "CircuitBreaker 'inventoryService' is OPEN".
4. Half-open lets 5 calls through; ongoing sellout rejections re-open the breaker → flapping.
5. Platform-wide checkout outage triggered by NORMAL sold-out behavior of a single hot item.

## Secondary defects in the same incident
- Error message leaks internal class details to API clients (breaker state text inside ORD-4007 message).
- 5 critical alerts fired (incl. CircuitBreakerOpen, OrderCommitStuck=18) but alertmanager's only receiver is `telegram-disabled` (container started without TELEGRAM_BOT_TOKEN env despite .env having it) → NO notification delivered. DatabaseUnavailable/RedisUnavailable alerts are false positives caused by the opt-in postgres/redis exporters being down (up{job="postgresql"}==0 fires while postgres is healthy).

## Classification
- Severity: P0 (availability + revenue; deterministic trigger under the exact audited Black Friday scenario).
- Confidence: CONFIRMED_BY_REPRODUCTION (live incident) + CONFIRMED_FROM_SOURCE (mechanism).
- Fix direction: configure `ignoreExceptions` (business 4xx domain exceptions, e.g. StockReservationFailedException, plus client 4xx statuses) on every downstream breaker instance — or record only 5xx/timeouts/IOException; add regression test: N consecutive stock-rejections must NOT open the breaker; add instance-specific config per client.
