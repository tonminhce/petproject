# Review Report — shipping-service + tax-service + promotion-service

> Scope: `shipping-service`, `tax-service`, `promotion-service` at `/Users/tonminh-mac/IdeaProjects/untitled5/`.
> Excluded: `media-service`, prior reports in `docs/review/`.
> Method: independent review of every Java file under `src/main/java` and `src/test/java` plus the service application.yml and Liquibase changelogs.

---

## Files Reviewed

**shipping-service** (38 main + 17 test = 55 Java files)
- controller/BackofficeShipmentController.java, webhook/CarrierWebhookController.java
- service/{ShipmentService,ShipmentStateMachine,ShipmentWriter,ShippingMetrics,WebhookEventService,WebhookEventWriter}.java
- service/impls/{ShipmentServiceImpl,WebhookEventServiceImpl}.java
- carrier/{CarrierAdapter,CarrierConfig,ManualCarrierAdapter,NoopCarrierAdapter}.java
- kafka/{OrderEventConsumer,ShippingListenerConfig}.java
- outbox/{OutboxEvent,OutboxEventRepository,ShippingEventPublisher,ShippingEventPublisherImpl,ShippingOutboxRelay}.java
- scheduler/{ReconciliationScheduler,WebhookRetryScheduler}.java
- webhook/{CarrierWebhookPayload,WebhookSignatureVerifier}.java
- entity/{Shipment,ShipmentEvent}.java, repository/{ShipmentRepository,ShipmentEventRepository}.java
- dto/{OrderLifecycleEvent,request/AssignTrackingRequest,request/ShipmentTransitionRequest,response/ShipmentResponse}.java
- constant/{Carrier,ShipmentStatus}.java, config/{ClockConfig,ShippingWebhookProperties}.java
- All tests under src/test/java/...
- Resources: application.yml, db/changelog/changelog-00{1,2,3}-*.yaml

**tax-service** (12 main + 11 test = 23 Java files)
- controller/{BackofficeTaxClassController,BackofficeTaxRateController,TaxCalculationController}.java
- service/{TaxCalculationService,TaxCalculator,TaxClassService,TaxRateService}.java
- service/impls/{TaxCalculationServiceImpl,TaxClassServiceImpl,TaxRateServiceImpl}.java
- entity/{TaxClass,TaxRate}.java, repository/{TaxClassRepository,TaxRateRepository}.java
- dto/request/{TaxCalculateRequest,TaxClassRequest,TaxRateRequest}.java, dto/response/{TaxCalculateResponse,TaxClassResponse,TaxRateResponse}.java
- All tests, application.yml, db/changelog/changelog-001-initial-schema.yaml

**promotion-service** (24 main + 14 test = 38 Java files)
- controller/{BackofficeCampaignController,PromotionReservationController}.java
- service/{CampaignReservationService,CampaignService,DiscountCalculator,OutboxRetentionScheduler,PromotionEventPublisher,PromotionMetrics,PromotionOutboxRelay,ReservationCleanupScheduler,ReservationRetryService}.java
- service/impls/{CampaignReservationServiceImpl,CampaignServiceImpl,ReservationRetryServiceImpl,TransactionalPromotionEventPublisher}.java
- entity/{Campaign,CouponUsageReservation,OutboxEvent}.java, repository/{CampaignRepository,CouponUsageReservationRepository,OutboxEventRepository}.java
- dto/request/{CampaignRequest,ReserveRequest}.java, dto/response/{CampaignResponse,CampaignUsageResponse,ReservationResponse}.java
- validation/{ValidDiscountValue,ValidDiscountValueValidator}.java, constant/{CampaignStatus,UsageStatus}.java
- All tests, application.yml, db/changelog/changelog-00{1,2}-*.yaml

Total: **116 Java files** reviewed across the three services.

---

## Critical Findings

### C1 — shipping-service: DB CHECK constraint rejects the new webhook-retry states
- **Constraint** — `shipping-service/src/main/resources/db/changelog/changelog-001-shipments.yaml:74`
  ```sql
  ALTER TABLE shipment_events ADD CONSTRAINT ck_shipment_events_status
    CHECK (status IN ('PROCESSED', 'FAILED'))
  ```
- **Code that breaks it** — `ShipmentEvent.java:33-34` adds `STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE"` and `STATUS_FAILED_PERMANENT = "FAILED_PERMANENT"`.
- **Write sites** — `WebhookEventServiceImpl.java:92` inserts every failed webhook as FAILED_RETRYABLE; `WebhookRetryScheduler.java:70, 92` flips to FAILED_PERMANENT.
- **Changelog-003** only does a one-shot UPDATE of historical rows (line 26-29); it does **not** `DROP`/alter the constraint. Every new failed webhook will raise `DataIntegrityViolationException` the moment the row is inserted; the retry scheduler cannot mark any row FAILED_PERMANENT.
- **Why tests do not catch it** — `WebhookEventServiceTest` mocks `ShipmentEventRepository`, `WebhookRetrySchedulerTest` mocks the repo too. Only `ShippingFlowIT` would exercise it; it never triggers a FAILED_RETRYABLE/FAILED_PERMANENT code path.

**Fix** — add to a new changelog:
```sql
ALTER TABLE shipment_events DROP CONSTRAINT ck_shipment_events_status;
ALTER TABLE shipment_events ADD CONSTRAINT ck_shipment_events_status
  CHECK (status IN ('PROCESSED', 'FAILED', 'FAILED_RETRYABLE', 'FAILED_PERMANENT'));
```

### C2 — shipping-service: `WebhookEventWriter.complete` hardcodes `autoDelivered=false`
- `WebhookEventWriter.java:30` — `publisher.publishDelivered(shipment, false);`
- The `delivered` boolean on line 26 is only used as a gate ("publish yes/no"); the autoDelivered flag that reaches the outbox payload is the **literal** `false`. Any future caller that wanted to distinguish "delivered via webhook" from "delivered via scheduler auto-sweep" cannot.
- Defensible today (the only caller is webhook-driven, never auto-sweep), but the signature promises a flag the code throws away.

### C3 — shipping-service: webhook status transition logic duplicated between entry path and retry path
- Entry path `WebhookEventServiceImpl.java:149-174` runs state-machine + persists + publishes.
- Retry path `WebhookEventServiceImpl.java:116-134` re-parses + calls `process()` (the same method).
- **Duplication risk** — the "process" method is shared, but the outer guards are duplicated: signature verification is in `handle()`, dedup query exists in both `handle()` and (implicitly) the retry pre-conditions. The retry path **does not** re-verify the HMAC signature (because it re-reads the persisted payload), but it also does not verify that the event is still FAILED_RETRYABLE — it just runs `process()` blindly. A row that was flipped to FAILED_PERMANENT between scheduling and execution could be resurrected.

### C4 — tax-service: country code lacks `@NotNull`/`@NotBlank`; null slips past validation
- `TaxCalculateRequest.java:11-13`:
  ```java
  @NotNull UUID taxClassId,
  @Pattern(regexp = "^[A-Z]{2}$") String country,
  String postalCode,
  @NotNull @DecimalMin("0.00") BigDecimal amount
  ```
- A missing `country` (`null`) does not match the pattern; `@Pattern` rejects it on null for some validator implementations. **However** a missing **field** (Jackson omits it entirely) leaves `country=null`; the service then queries `taxRateRepository.findByTaxClassIdAndCountryAndPostalCodeIsNull(classId, null)` — unpredicted behavior.
- Same in `TaxRateRequest.java:13`.
- **Fix** — add `@NotNull` (or `@NotBlank`) to `country`.

### C5 — tax-service: `TaxCalculationController` mounted under BACKOFFICE path with SERVICE gate
- `TaxCalculationController.java:22-23` — `@PostMapping(ApiPaths.BACKOFFICE_TAX_RATES + "/calculate")` with `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")`.
- A backoffice URL doing service-to-service is contradictory and breaks the storefront-vs-backoffice convention in rule 5. Anyone with ADMIN role can hit it as a side door (the audit test confirms this — `BackofficeTaxAuditTest.calculate_byServiceToken_emitsAuditLineWithServiceActor`, but an ADMIN actor would land as `actorType=user`, not `service`).
- **Fix** — move under a dedicated path (e.g. `/api/v1/tax/calculate`, missing from `ApiPaths`) and keep SERVICE/ADMIN gate there.

### C6 — promotion-service: reserve() has no idempotency key — retries produce orphan reservations
- `CampaignReservationServiceImpl.java:56-115` `reserve(code, request)` keys on (campaign, userId, orderId) only. A service retry with the same body creates a **new** PENDING row each call.
- The controller `PromotionReservationController.reserve` accepts duplicate `(userId, orderId)` pairs. Order-service saga retry would create a second PENDING row that, if the first commits, leaves an orphan that the cleanup scheduler eventually purges.
- Worse: a per_user_limit=2 user who makes 3 separate reserve calls all see the first 2 succeed (each as a fresh PENDING), then the third is rejected — but they now have 2 reservations against the same orderId, only 1 of which can be COMMITTED.
- **Fix** — accept an `Idempotency-Key` header (or derive one from request hash) and short-circuit on existing match, mirroring the order-service `ORDER_DUPLICATE_REQUEST` (ORD-4010) pattern.

### C7 — promotion-service: ReservationCleanupScheduler test asserts behavior the code does not have
- `ReservationCleanupScheduler.java` — `releaseAllExpiredReservations` uses a `TransactionTemplate` and never calls `entityManager.flush()` / `entityManager.clear()` directly.
- `ReservationCleanupSchedulerTest.java:75-77, 136-137` asserts `verify(entityManager, times(1)).flush(); verify(entityManager, times(1)).clear();` — neither call exists in production code.
- **Effect** — either the test silently fails (likely), or the production code is silently missing the persistence-context flushes the test was supposed to enforce. Either way, the test gives **false confidence** about the batch-loop persistence-context lifecycle on a backlogged run.
- **Fix** — either drop the verifies (and rename the test), or add `@PersistenceContext EntityManager` to the scheduler and call `flush()/clear()` after every non-empty batch.

---

## High Findings

### H1 — shipping-service: `ShipmentEvent.STATUS_FAILED` is dead code
- `ShipmentEvent.java:31` — `public static final String STATUS_FAILED = "FAILED";`
- Replaced by FAILED_RETRYABLE/FAILED_PERMANENT. No production reference (only `WebhookEventWriterTest.java:58` uses it to construct a fixture). DB CHECK still allows `FAILED`; the changelog-003 UPDATE migrated all FAILED rows but the constant lives on. Drop or document as "legacy".

### H2 — shipping-service: `ShipmentServiceImpl.findAll(orderId)` fabricates page metadata
- `ShipmentServiceImpl.java:88-94` — when `orderId` is supplied, `new PageImpl<>(content, pageable, content.size())` is returned. `totalElements` equals `content.size()` (always 0 or 1 since `uk_shipment_order_live` is unique), `totalPages` becomes 1.
- But the `pageable` argument may say `page=2, size=20` — the client thinks there are more pages, only to receive empty results. Inconsistent semantics between the orderId branch and the status/carrier branches.
- **Fix** — return `List<ShipmentResponse>` directly (drop `ApiResponse<PageResponse<...>>` envelope for orderId), or short-circuit `page` to 0.

### H3 — shipping-service: `ShipmentStateMachine` is a static class — legal transitions duplicated
- `ShipmentStateMachine.java:10-34` — fine as utility, but the legal transitions are hard-coded in two places (here and in `ShipmentStateMachineTest.java:22-34`). Any state-machine change must be done in lockstep.
- Minor DRY concern — promote to a shared constant or a YAML file.

### H4 — shipping-service: `ShipmentServiceImpl.advance` is not `@Transactional` — multi-step write is split across 3 micro-txs
- `ShipmentServiceImpl.java:149-164` `advance` is NOT `@Transactional`. It relies on the caller being `@Transactional` (the controller does not wrap it either — the controller has no class-level tx). The `save()` / `saveDelivered()` in `ShipmentWriter` are `@Transactional`, so each write is its own micro-tx. Result: a sequence of `setStatus / setPreviousStatus / setLastCarrierUpdate / save` followed by metrics is **not atomic** with the DB write; if metrics emit fails, the write is already committed.
- Also: `advance` mutates `shipment` in-place **before** `writer.save`. If a concurrent reader runs in another tx, they see pre-mutation state (which is correct in MVCC), but the writer flushes a value that may differ from what was set if another setter runs in the same instance.
- **Fix** — annotate `advance` `@Transactional` so the status flip and metrics are in one boundary.

### H5 — tax-service: list endpoints return raw `List`, no pagination / cap
- `BackofficeTaxClassController.java:32-35` — `@GetMapping public ApiResponse<List<TaxClassResponse>> list()`
- `BackofficeTaxRateController.java:33-36` — `@GetMapping public ApiResponse<List<TaxRateResponse>> list(@RequestParam(required = false) UUID classId)`
- Pattern rule 2 (PageResponse envelope) and rule 4 (page-size cap) are **violated**. `TaxClassRepository.findAll()` and `TaxRateRepository.findAllByTaxClassId()` are unbounded; with many rows this will OOM the JVM.

### H6 — tax-service: missing auditor surfaces as 500
- `TaxClassServiceImpl.java:76` and `TaxRateServiceImpl.java:76` — `auditorAware.getCurrentAuditor().orElseThrow()` throws `NoSuchElementException`. No handler catches it; `ApiExceptionHandler` does not have a fallback for it. Production endpoint returns 500.
- Confirmed by `TaxClassServiceImplTest.java:184` — test explicitly asserts `NoSuchElementException`. This is a 5xx surfacing from a backoffice route.

### H7 — tax-service: `TaxClassRepository` has no `findAllByDeletedFalse` — relies on @SQLRestriction
- `TaxClassRepository.java:11-13` — `findByNameIgnoreCase(name)` returns rows including soft-deleted. Because the entity has `@SQLRestriction("deleted = false")`, the SQL appended is correct (deleted = false AND lower(name) = lower(?)). OK by side-effect — but the derived name suggests it does a plain name match. Subtle: a soft-deleted class with the same name cannot be detected by this method, which is exactly the intent.

### H8 — tax-service: `TaxCalculateResponse` does not include `taxClassId` — caller cannot correlate
- `TaxCalculateResponse.java:3` — record `(BigDecimal taxAmount, BigDecimal appliedRate)`. No id of the class or rate used. The caller (order-service) must remember which classId/country/postalCode the request used. Round-trip is impossible (idempotency).

### H9 — promotion-service: `OutboxRetentionScheduler` purges only SENT — FAILED rows accumulate forever
- `OutboxRetentionScheduler.java:28-37` — purges only SENT. The class doc says "FAILED rows are NOT purged: they need manual root-cause before deletion (ops runbook)". No metric on FAILED backlog size, no alerting.
- A long-running FAILED accumulation will eventually OOM the DB.

### H10 — promotion-service: reserve() race-protection conflates audit and concurrency
- `CampaignReservationServiceImpl.java:99-100` — `campaign.setUpdatedAt(now); campaignRepository.saveAndFlush(campaign);`
- `updatedAt` is normally filled by JPA auditing (`@LastModifiedDate`); a manual `setUpdatedAt` for race-protection pollutes audit timestamps. Every reserve attempt (successful or not) bumps `updatedAt`; the JPA auditing will not increment version unless a mapped field actually changes.
- More importantly: setting `updatedAt` **alone** does NOT cause the @Version to bump — JPA uses dirty-tracking. If only `updatedAt` is touched, Hibernate flushes the UPDATE and @Version increments. OK; the doc explains this.
- **Suggestion** — a dedicated `@Version` field is already present (`Campaign.java:58-61`); the workaround of touching `updatedAt` is the documented technique. It works, but the smell (audit field doing double duty) is worth flagging.

### H11 — promotion-service: `ReservationCleanupScheduler` is not `@Scheduled`-locked — multi-instance hazard
- Multiple service instances would all run the sweep simultaneously. The status flip is idempotent (PENDING → EXPIRED), but the `existsByCampaignIdAndStatusIn` check in `delete` plus the FAILED race condition makes the row conflict harmless. **However** no `@SchedulerLock` / ShedLock annotation guards against it.
- For the same reason, `PromotionOutboxRelay.java` (`@Scheduled`) and `WebhookRetryScheduler.java` (shipping) have the same multi-instance hazard.

### H12 — promotion-service: `PromotionReservationController.releaseCommittedWithRetry` returns 200 with `data=null`
- `PromotionReservationController.java:60` — `return ApiResponse.ok(null);`. The `@Audited` annotation should record this; an HTTP 200 with null body is fine for a release ack, but the inconsistency with `release` (`ApiResponse.message(...)`, line 52) is a code smell.

---

## Medium Findings

### M1 — shipping-service: `OrderLifecycleEvent` and `CarrierWebhookPayload` are mutable POJOs (Lombok @Setter)
- `shipping-service/.../dto/OrderLifecycleEvent.java` — `@Getter @Setter @NoArgsConstructor` mutable class.
- `shipping-service/.../webhook/CarrierWebhookPayload.java` — same shape.
- Tax/promotion DTOs are all records. **Inconsistency** with platform convention.

### M2 — shipping-service: `ShipmentRepository` redeclares inherited methods
- `ShipmentRepository.java:18, 20, 22` — `Optional<Shipment> findById(UUID)` and friends are inherited from `JpaRepository`. Redundant but harmless.

### M3 — shipping-service: `WebhookEventServiceImpl.handle` has 50-line method with mixed responsibilities
- `WebhookEventServiceImpl.java:58-113` — HMAC verify, parse, dedup, insert-with-race-handle, process-with-error-trap. The error path returns silently, but a Carrier configuration error returns 401. Mixed flow control — extract signature-verify helper and parse-or-persist helper.

### M4 — shipping-service: `BackofficeShipmentController.findAll` filter branches use chained `if/else`
- `ShipmentServiceImpl.java:95-102` — the chained `if status / else if carrier / else` is a Strategy smell. Should be Specification or a single dynamic query.

### M5 — shipping-service: `ShipmentServiceImpl.cancelShipment` swallows "shipment in flight" silently
- `ShipmentServiceImpl.java:74-85` — if shipment exists but is not CREATED, just log "leaving untouched" and return. No business event, no audit, no `@Audited` annotation. Operator cannot reconstruct why a CANCELLED order still has a PICKED_UP shipment.

### M6 — shipping-service: `CarrierWebhookController` exposes `/{carrier}` accepting any string
- `CarrierWebhookController.java:23-29` — `@PathVariable String carrier` accepts any value; `WebhookEventServiceImpl.handle` resolves `Carrier.valueOf(carrier)` and on failure throws `SHIPPING_WEBHOOK_SIGNATURE_INVALID` (401). Side-channel: an attacker can probe for valid carrier names by 401-vs-404 differences. Low risk because secret-verify fails before any DB action.

### M7 — shipping-service: `ShippingOutboxRelay` mutates then saves — no `saveAndFlush`
- `ShippingOutboxRelay.java:42-43` — on success, the row is saved without flush, so the `findByStatusOrderByIdAsc(...,PENDING,...)` next poll may still see the just-SENT row (until the tx commits). The 2-second poll interval normally hides this, but a same-tick retry by an external tool could re-publish.
- Same pattern in `PromotionOutboxRelay.java:67, 79`.

### M8 — tax-service: precision/scale mismatch on percentage fields
- `TaxClass.java:24-25` — `precision=5, scale=2` means values up to 999.99 fit. The request validation caps at 100.00. The mismatch is **only theoretical** because the controller @Valid prevents bad values. But direct SQL INSERT could store 999.99%. A `@DecimalMax` on the entity field would be defense in depth.

### M9 — tax-service: `taxRate` uniqueness relies on `countDuplicate` (a count query) instead of relying on the DB to throw
- `TaxRateRepository.java:28-34` — defensive pre-check using a `count(r)` JPQL. Race-prone: two concurrent `create` calls for the same (class, country, postal) both pass the count, both INSERT, the second hits the unique index (assumed) and throws `DataIntegrityViolationException`. No catch.
- **Fix** — wrap insert in try/catch and translate to `DUPLICATE_TAX_RATE`.

### M10 — tax-service: `TaxCalculationServiceImpl` does both DB lookups separately — 2 round trips
- `TaxCalculationServiceImpl.java:31-40` — first `findById(taxClassId)` (1 SELECT), then `findByTaxClassIdAndCountryAndPostalCode(...)` + `findByTaxClassIdAndCountryAndPostalCodeIsNull(...)` (2 more). Three round trips per `calculate` call. Service-to-service hot path — small but cumulative.

### M11 — tax-service: no `@Cacheable` on `TaxClass` lookups (compare with product/inventory)
- `TaxCalculationServiceImpl.calculate` reads the same `TaxClass` row thousands of times during order confirm. Tax classes are tiny (~10s) and rarely change. A simple `@Cacheable` on `findById` would cut p99 by 1 DB hop.
- Out of scope per rule "no cache starter", but worth noting.

### M12 — tax-service: `@Audited` on TaxCalculationController but resourceType="tax-calculation" — no resourceId
- Audit semantics: the response does not carry a resourceId, so audit trail has actor/service+action only. Acceptable for stateless calls, but post-mortem query ("who calculated taxes for orderId X") requires a payload lookup. Minor.

### M13 — promotion-service: `CouponUsageReservation` does not extend `AbstractMappedEntity` — but reservation rows are hard-deleted by retention scheduler
- `CouponUsageReservation.java` — hard-delete entity, no soft-delete, no `@CreatedDate`. The intent is documented (line 22-26). OK by design.
- **However** `reservedAt` is set manually by the application (no auditing auto-fill). Drift risk if the column is later changed to a different default.

### M14 — promotion-service: `reserve()` runs 3 SELECTs + 1 UPDATE + 1 INSERT + 1 outbox INSERT — 6 round trips
- `CampaignReservationServiceImpl.java:57-90` — every reserve is 6 DB ops. Combined with M10 (tax), this is the inner loop of order-creation. Worth a `JOIN FETCH` on the campaign + reservation counts could cut to 2 round trips.

### M15 — promotion-service: `ReservationRetryServiceImpl.sleep` swallows `InterruptedException`
- `ReservationRetryServiceImpl.java:86-92` — restores interrupt flag and continues. Acceptable but the retry loop is now non-interruptible across the whole transaction.

### M16 — promotion-service: `PromotionOutboxRelay.relay` runs in single thread — head-of-line blocking
- `PromotionOutboxRelay.java:80` — `break` on first failure. Documented as deliberate. Under sustained broker downtime the relay stops draining — PENDING backlog grows; no metric on `outbox-stuck-duration`.

### M17 — promotion-service: `CampaignResponse.from` returns `createdAt` and `updatedAt` directly — but `updatedAt` is bumped on every reserve (see H10)
- Auditors see an `updatedAt` cadence matching reservation traffic, not actual campaign edits. Audit pollution.

### M18 — promotion-service: `ValidDiscountValueValidator` does not check that `discountValue` is non-null when discountType is `FIXED`
- `ValidDiscountValueValidator.java:18-20` — `if (request == null || request.discountType() == null || request.discountValue() == null) return true;`
- Subtle: `discountValue=null` + `discountType="FIXED"` → passes. Field `@NotNull @DecimalMin("0")` on discountValue (request) handles it. OK by validation stack but the validator silently passes.

---

## Low Findings

### L1 — shipping-service: `ShipmentStatus.inFlight` reused by `ReconciliationScheduler` via method reference
- `ShipmentStatus.java:6-8` — used in `ReconciliationScheduler.java:25-27`. Fine.

### L2 — shipping-service: `OrderLifecycleEvent` has `@JsonIgnoreProperties(ignoreUnknown=true)` but does NOT `@JsonInclude(NON_NULL)`
- Optional fields `eventId, eventType, occurredAt` will be serialized as nulls. Tax/promotion request DTOs use records where Jackson handles nulls cleanly. Inconsistency.

### L3 — shipping-service: `ClockConfig` returns `Clock.systemUTC()`; `TestClockConfig` replaces with fixed
- `shipping-service/.../config/ClockConfig.java:14` — production bean. `TestClockConfig` correctly overrides via `@Primary`. Fine.

### L4 — shipping-service: `BackofficeShipmentController` returns `Page<ShipmentResponse>` from the service and converts in the controller
- `BackofficeShipmentController.java:45-47` — service returns Spring `Page`, controller maps to `PageResponse`. Boundary violation: the controller should not see Spring `Page` (the rule is the service returns `PageResponse<T>`).
- Promotion does the same — see L7.

### L5 — shipping-service: `ShipmentEntity` uses `GenerationType.UUID`
- `Shipment.java:33` — `@Id private UUID id;` without `@GeneratedValue`. The application code does `.id(UUID.randomUUID())` explicitly. Works but inconsistent with promotion's `@GeneratedValue(strategy = GenerationType.UUID)`.

### L6 — shipping-service: `@Audited` annotations use action strings
- All three services have `@Audited` annotations. The strings (`shipment.transition`, `tax-class.create`, etc.) are consistent with the codebase convention. ✓

### L7 — promotion-service: `BackofficeCampaignController.findAll` also leaks Spring `Page<T>` to controller
- `BackofficeCampaignController.java:46-55` — controller converts to `PageResponse` itself. Same as L4. Convention is for the service to return the wire format. (Note: tax's list returns raw `List` — H5 covers that.)

### L8 — promotion-service: `PromotionReservationController.state` GET endpoint — no caching
- `PromotionReservationController.java:63-67` — read-only state for polling. No Cache-Control, no ETag. Reconciliation client will poll repeatedly.

### L9 — promotion-service: `CampaignReservationServiceImpl.releaseCommitted` allows COMMITTED → RELEASED for **expired** rows
- `CampaignReservationServiceImpl.java:178-194` — does NOT check expiresAt. A user can `releaseCommitted` a reservation that has been expired by the cleanup scheduler. Probably fine (the cleanup sets status=EXPIRED, so the gate at line 181 rejects it), but worth a test.

### L10 — promotion-service: `TransactionalPromotionEventPublisher` writes outbox in the same tx — atomicity is correct
- `TransactionalPromotionEventPublisher.java:103-108` — JSON serialization failure throws `IllegalStateException`, which propagates up and rolls back the surrounding reservation transaction. **Correct behavior** (atomicity preserved). Good.

### L11 — promotion-service: `DiscountCalculator.compute` throws `IllegalStateException` on unknown type — propagates as 500
- `DiscountCalculator.java:32` — no `BusinessException` wrapper. Combined with the @Pattern validation in `CampaignRequest` (which limits to PERCENT|FIXED), this is unreachable in practice; defense in depth would catch a future schema drift.

### L12 — tax-service: `TaxRateRepository.countByClassId` uses a JPQL — could be a derived method
- `TaxRateRepository.java:20-21` — `@Query("select count(r) from TaxRate r where r.taxClassId = :classId") long countByClassId(...)` — a derived method `long countByTaxClassId(UUID classId)` would suffice. Minor style nit.

### L13 — tax-service: `TaxCalculationServiceImpl` is missing `Optional`/`Stream` short-circuits
- `TaxCalculationServiceImpl.java:34-48` — `resolveRatePct` chains two repository lookups, then falls back to `taxClass.getDefaultRatePct()`. If the class has no default (line 37-39), throws NO_MATCHING_RATE (404). OK, but `ratePct == null` check is implicit ("defaultRatePct may be null if schema allows") — relies on the @NotNull constraint on the column at the DB layer. **TaxCalculationIT.noRateAnywhereThrowsTax8002** has to drop the NOT NULL constraint first.

### L14 — promotion-service: `@Audited` on PromotionReservationController.reserve — but `resourceId` is null
- `PromotionReservationController.java:33-37` — `resourceId=null` in the audit log. Confirmed by `PromotionReservationAuditTest.java:88`. Documented by design (campaign code is not a stable resourceId), but audit consumers cannot easily find "all reservations for code X" later.

### L15 — shipping-service: `WebhookRetryScheduler.backoffSeconds` is exponential with no jitter
- `WebhookRetryScheduler.java:106-112` — multiplies `BASE_BACKOFF_SECONDS=300` by 5x per attempt. With 6 attempts, the last retry waits 5^5 * 300 = 9,375,000 seconds (~109 days). Effectively a poison-pill — anything past attempt 5 will never run again until the cron fires. Same on payment-service presumably; out of scope.

### L16 — promotion-service: `@Audited` on PromotionReservationController.commit/release uses path variable `reservationId` — good
- ✓ — verified in audit tests.

### L17 — promotion-service: `CampaignServiceImpl.applyStatus` PUT semantics — null status → INACTIVE
- `CampaignServiceImpl.java:134-140` — `else campaign.deactivate();`. PUT semantics on a nullable status is fine, but the asymmetry (null → INACTIVE, ACTIVE → ACTIVE) is documented inline. OK.

### L18 — promotion-service: `CampaignRepository.findAllByStatus` uses derived method — fine.

---

## Pattern Compliance Summary

| # | Rule | shipping | tax | promotion |
|---|---|---|---|---|
| 1 | Layer controller→service→impls→repository→entity; request+response records | OK DTOs are records (except M1) | OK all records | OK all records |
| 2 | `ApiResponse<T>`/`PageResponse<T>` envelope | OK mostly | FAIL list endpoints return raw `List` (H5) | OK |
| 3 | `@RequestMapping(ApiPaths.*)` | OK | OK | OK |
| 4 | Cap page size via `Math.min(size, MAX_PAGE_SIZE)` | OK | FAIL list endpoints no page params (H5) | OK |
| 5 | Storefront no `@PreAuthorize`; backoffice class-level ADMIN | OK (only backoffice; class-level ADMIN) | FAIL `TaxCalculationController` on BACKOFFICE path with SERVICE+ADMIN gate (C5) | OK backoffice class-level; storefront reservation per-method |
| 6 | `AuthenticatedUser.requireCurrent().id()` | N/A (no user-id-keyed operations) | N/A | N/A (no actor from JWT recorded in reservation rows — orderId carries the actor through the saga) |
| 7 | `@Transactional(readOnly=true)`; soft-delete @SQLRestriction | OK both | OK both | OK Campaign uses SQLRestriction; `CouponUsageReservation` + `OutboxEvent` use hard-delete by design (documented) |
| 8 | `ddl-auto: validate` + Liquibase | OK | OK | OK |
| 9 | ENV_VAR default yml; mapper/; outbox pattern | OK has outbox (`ShippingEventPublisher`/`ShippingOutboxRelay`); no `mapper/` (uses Java records → static `from(...)` factories) | OK no outbox (no events); no `mapper/` | OK has outbox; no `mapper/` |

**Net score**: All three services are **largely compliant** with the 9 rules. Shipping has one critical DB/code divergence (C1) and a duplicated webhook transition path (C3); tax has two endpoint-level violations of rules 2 and 4 (H5) plus a role-path mismatch (C5); promotion has the strongest structural compliance but the test/impl mismatch (C7) and the idempotency gap (C6) are concerning.

---

## Top 5 Issues to fix now

1. **C1 (shipping) — DB CHECK constraint rejects new webhook statuses.** Production code will throw `DataIntegrityViolationException` on every failed webhook insert. Drop and re-add the constraint in a new changelog.
2. **C6 (promotion) — Reserve endpoint has no idempotency key.** Service retries produce orphan reservations that leak quota and pollute the cleanup backlog. Add an `Idempotency-Key` header (or hash-based derivation).
3. **C7 (promotion) — `ReservationCleanupSchedulerTest` asserts `entityManager.flush()/clear()` that the scheduler never calls.** Either the test is silently failing or the production code is missing the persistence-context flushes. Resolve before next deploy.
4. **C5 (tax) — `TaxCalculationController` mounted under BACKOFFICE path with SERVICE gate.** Move to a dedicated path (`/api/v1/tax/calculate`) to align with storefront-vs-backoffice convention.
5. **H5 (tax) — list endpoints return raw `List`, no pagination cap.** Violates rule 2 + 4; will OOM the service at scale. Wrap in `PageResponse<T>` + `Math.min(size, MAX_PAGE_SIZE)`.

---

## Summary by Service

| Service | Critical | High | Medium | Low |
|---|---|---|---|---|
| shipping-service | 3 (C1, C2, C3) | 4 (H1-H4) | 7 (M1-M7) | 7 (L1, L2, L3, L4, L5, L6, L15) |
| tax-service | 2 (C4, C5) | 4 (H5-H8) | 5 (M8-M12) | 2 (L12, L13) |
| promotion-service | 2 (C6, C7) | 4 (H9-H12) | 6 (M13-M18) | 9 (L7, L8, L9, L10, L11, L14, L16, L17, L18) |
| **Totals** | **7** | **12** | **18** | **18** |

**Overall**: Code is high-quality, well-documented, and pattern-compliant at the structural level. The shipping-service has one genuine production-incident bug (C1) plus an architectural duplication (C3). The tax-service is functionally correct but breaks two controller-layer pattern rules (H5) and has an authorization path mismatch (C5). The promotion-service has the cleanest architecture but carries the highest business risk in idempotency (C6) and a test-impl mismatch that hides scheduler correctness (C7).