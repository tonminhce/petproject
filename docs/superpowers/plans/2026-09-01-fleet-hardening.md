# Fleet Hardening — Production Flag Sweep (2026-09-01)

Branch: `feature/fleet-hardening` (worktree `../untitled5-hard`), BASE `cb4b693` (media merge).
Objective: fix ALL open flags from the media epic final review + PRODUCTION-READINESS.md Future hardening so the 14-service fleet is production-grade.

## Binding constraints (whole plan)

- Fleet posture unchanged: no-DLT containment (unknown types ack-skip INFO; poison contained), fail-safe on dependency outage, private buckets, interface+impls split, meters, i18n EN+VI for user-facing errors, Liquibase chained changesets.
- Kafka: the ONLY sanctioned wire is producer `JsonKafkaSerializer` (double-encoded String). Every consumer MUST tolerate the real wire. IT helpers MUST publish through the real fleet path.
- No new infra (no new broker/topic, no new service). Product internal endpoints are SERVICE-token gated.
- Battery must include `utils/*` common modules after this epic.
- Every task: full suite green for touched services + `docker compose config -q` green if compose touched + `./mvnw -T1C install -DskipTests` exit 0.

## Preflight facts (verified, file:line)

- `utils/common-kafka/.../BaseKafkaListenerConfig.java:63-70` hard-wires Spring `JsonDeserializer` for 4 typed subclasses: product `RatingLifecycleListenerConfig` (rating lifecycle), shipping `ShippingListenerConfig` (order lifecycle), notification `NotificationListenerConfig` (order lifecycle), order `ShippingListenerConfig` (shipping lifecycle). Producer relays are double-encoded (`JsonKafkaSerializer.java:38`) → these consumers are candidate silent-droppers (same class of bug as search F-5). IT helpers for notification (`NotificationFlowIT.java:63-64`) and shipping (`ShippingFlowIT.java:108-109`) are BLIND (StringSerializer value).
- F-5-fixed pattern: search `SearchListenerConfig.java:37-43` + product `MediaLifecycleListenerConfig.java:31-43` (StringDeserializer both, self-unwrap tolerant single/double).
- common-spring `CommonLibraryStarterTests` fails: `jpaAuditingHandler → jpaMappingContext` — `@EnableJpaAuditing` on shared starter breaks non-JPA contexts (test excludes DataSource/Hibernate autoconfig).
- Product PUT partial update: `ProductMapper.java:77-89` null-guard per field; `:86` mediaId null = no-op → cannot clear. `assertMediaExists` skips null.
- `MediaDeletedConsumer.java:45-60`: every failure path acks (ack-always).
- Audit: prod overlay `x-audit-env`/`x-audit-volume` covers 8 services (product, rating, tax, notification, shipping, payment, search, promotion); **media + inventory emit @Audited but are NOT wired** (`docker-compose.prod.yml:34-39`).
- `.env.example`: ~50 vars are `<your-value>` (POSTGRES_HOST:11, STORAGE_*, JWT_ISSUER_URI:55, REST_CLIENT_* ints …) → direct `cp` breaks compose.
- `docker-compose.yml:140` `rustfs/rustfs:latest` UNPINNED (only one).
- R1: 4 manual `RestClient.Builder` interceptor sites (order `RestClientConfig.java:75-77`, rating `:54-55`, search `ProductClientConfig.java:55-56`, product `MediaClientConfig.java:49-50`); seam = `TracingAutoConfiguration.java:38-46` customizer; no RestClientAutoConfiguration exists; `.env.example:123` references it (stale).
- confirm(): `OrderStatusController.java:28-34` parses principal id as UUID, semantically "admin id" while SERVICE tokens carry service-account UUID (PRODUCTION-READINESS.md:157-159).
- Bucket divergence: presign via 2-arg = `shop.storage.bucket` chain (`MediaQueryServiceImpl.java:66`), upload/purge explicit `media.bucket` (`MediaUploadServiceImpl.java:153`, `MediaPurgeJob.java:61`) — same env today, two trees.
- Relay: media is the ONLY relay replaying FAILED (`MediaOutboxRelay.java:48-49`); no aging/terminal state; retention pattern to copy: inventory `OutboxRetentionScheduler.java:26-38` (SENT>7d purge, FAILED kept).
- i18n: no fleet-wide ErrorCode↔key linkage test; best pattern media `MediaI18nKeysTest.java:20-31` (hardcoded KEYS × 2 bundles).
- Surefire `**/*IT.java` include in 11 service poms (root failsafe binding never activates) — ITs run under `mvn test` (battery contract).

## Tasks

### Task 1 — Fleet Kafka deserialization hardening (common-kafka + 4 typed consumers)
1. Flip `BaseKafkaListenerConfig` to StringDeserializer×2 + move unwrap/typed-bind into `BaseKafkaConsumer` boundary (tolerant of single- AND double-encoded; typed handler conversion failures = contained ack-skip, same containment semantics). Subclasses unchanged (drop their deserializer expectations if needed).
2. Real-wire ITs for all 4 typed consumers (helper publishes via fleet `KafkaTemplate`/`JsonKafkaSerializer` — mirror product `MediaDeletedConsumerIT.java:56`): product←rating lifecycle, shipping←order, notification←order, order←shipping delivered. Fix any consumer actually broken by the double-encode (expected: they are).
3. Double-encoded-token pin test per service (raw wire shape asserted).
4. Blind IT helpers (notification, shipping) switched to real wire.
5. common-kafka unit tests updated; `TraceparentHeaderExtractionTest` unaffected (by-design blind is fine for a tracing test).

### Task 2 — Product media integrity hardening
1. `ProductUpdateRequest` + mapper: add explicit `clearMediaId` boolean (ruling H-2; categoryId/brandId out of scope). clear=true → set null, skip HEAD check; audit + i18n untouched; mapper test + controller test (clear persists, absent flag = no-op, clear+mediaId together = 400 MED-style binding error or validation message).
2. `MediaDeletedConsumer`: bounded in-consumer retry (3 attempts, short backoff) for TRANSIENT failures (DataAccess/TransientDataAccess) before ERROR+ack; unknown/poison stays immediate ack-skip. Tests: transient→retries→success; exhausted→ERROR+ack (posture preserved).
3. **Reconciliation sweep** (product): bounded scheduled job (config-gated, default on in prod, limit N=100/cycle, cron) — page products with non-null media_id, MediaHeadClient check (batch-friendly: per-row HEAD ok at N=100), 404 → clear + publishUpdated; outage → skip cycle + WARN fail-safe; meter `product_media_sweep_cleared_total` + last-result log. IT with WireMock 404/200/down.

### Task 3 — Media purge productionization (real reference checker)
1. Product internal endpoint: `GET /internal/products/media-references/{mediaId}` → `{mediaId, referenceCount}` (SERVICE-gated, follows internal-endpoint conventions; count query indexed on media_id).
2. Media `MediaReferenceClient` (EligibilityClient pattern: service token, timeout, fail-closed) + real `MediaReferenceChecker` wired as the prod bean: checker outage/failure → fail-safe skip+WARN (purge never hard-deletes on doubt). NoopMediaReferenceChecker removed from prod wiring (tests keep explicit stubs).
3. referenceChecker call inside purge try: checker exception → referenced=true + WARN (cycle never crashes).
4. Relay aging: extend media outbox with terminal state handling — nightly retention scheduler (copy inventory pattern): SENT>7d purge; FAILED>7d → DEAD (terminal, metric + WARN) while replay continues for younger FAILED.
5. Bucket unification: `MediaQueryServiceImpl` presign switched to explicit-bucket 3-arg call with `media.bucket` (one source of truth); unit test asserts upload/read/delete all use the same bucket property.
6. Quality debt: VariantRenderer unreachable-500 cleanup; BucketBootstrap javadoc truth; extra tests: undecodable-but-magic-valid upload → 415, persist-failure → orphan purge path, dedup concurrent race (unique violation → duplicate:true), no-upscale small image; i18n↔ErrorCode linkage test pattern (derive from media ErrorCode values, both bundles) as fleet reference.

### Task 4 — common-spring JPA auditing fix + battery scope
1. Fix `CommonLibraryStarterTests` root cause: move `@EnableJpaAuditing` off the shared starter into a conditional config (`@ConditionalOnClass` JPA present) — services with JPA unaffected (assert a JPA service context still boots with auditing: product context test or existing ITs prove it).
2. Add `utils/common-core, common-spring, common-kafka, common-keycloak, common-logging, common-security, common-storage` to the battery script/runbook so common modules are never silently red again.

### Task 5 — R1 central OTel wiring (BeanPostProcessor)
1. common-spring `TracingAutoConfiguration`: add `BeanPostProcessor` that applies the traceparent interceptor to EVERY `RestClient.Builder` bean post-creation.
2. Remove the 4 manual interceptor sites (order, rating, search ProductClientConfig, product MediaClientConfig) — builders stay, hand-added interceptor lines go.
3. Unit test: arbitrary `RestClient.Builder` bean gets interceptor without any service wiring.
4. `.env.example:123` stale reference fixed as part of T6 env work.

### Task 6 — confirm() actor semantic + infra/docs/env sweep
1. confirm(): actor label by token type — ADMIN → sub, SERVICE → `service:<azp>`; idempotency rows/audit store the string actor (no more service-account UUID misattribution). Order tests updated (KC26-shaped tokens exist in tests).
2. Audit volume: add media-service + inventory-service to prod overlay x-audit-env/x-audit-volume; PRODUCTION-READINESS.md §2 note updated (media wired).
3. `.env.example`: replace ALL `<your-value>` with working local-compose defaults (postgres/redis/kafka hostnames, storage, keycloak, ints) — direct `cp .env.example .env && docker compose up` boots; keep a `# CHANGE IN PROD` marker on secrets; fix stale RestClientAutoConfiguration reference line.
4. `rustfs/rustfs:latest` → pinned stable tag.
5. Docs sweep: `start-docker.sh:235`, `docker-README.md:94` stale `:8083`; PRODUCTION-READINESS.md "Future hardening" rewritten (R1 closed, confirm() closed, actor-coupling runbook stays, add: surefire-IT contract note, Kafka wire-shape contract note — JsonKafkaSerializer double-encode is the ONLY sanctioned wire).
6. `docker compose config -q` (base + prod merge) green; keycloak realm unchanged.

### Task 7 — Final whole-branch review (BASE cb4b693 → HEAD)
Fresh reviewer: cross-cutting audit (Kafka wire contract fleet-wide, purge safety, sweep correctness, audit semantic, env defaults actually boot) + triage residuals. Then merge --no-ff at main root, battery (incl. utils modules), push, cleanup.

## Rulings (controller, user-vetoable)

- H-1: `BaseKafkaListenerConfig` flips to String+unwrap-tolerant base — removes the footgun CLASS (all 4 typed consumers fixed by construction, real-wire ITs prove it).
- H-2: mediaId clearing = explicit `clearMediaId` boolean flag (record DTO cannot distinguish absent vs null without Optional churn; unambiguous + auditable). categoryId/brandId same limitation = out of scope this epic.
- H-3: Reconciliation sweep is product-side, bounded, fail-safe on media outage — closes at-most-once consumer windows durably.
- H-4: Real reference checker = product internal count endpoint + media client; checker failure = skip+WARN (purge fail-safe preserved); Noop removed from prod wiring.
- H-5: Media relay keeps FAILED replay + gains terminal DEAD aging (7d) — only media replays FAILED (other relays PENDING-only stay as-is, out of scope).
- H-6: confirm() actor string: ADMIN→sub, SERVICE→`service:<azp>`.
- H-7: Audit volume wiring extended to media + inventory (all 10 audited services covered in prod).
- H-8: `.env.example` ships working local defaults (cp-and-boot contract), secrets marked CHANGE-IN-PROD.
- H-9: Surefire IT-in-`mvn test` contract kept (battery depends on it) — documented, not restructured.
- H-10: rustfs image pinned.
- H-11: Battery scope gains utils/* common modules permanently.
