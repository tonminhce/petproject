# Architecture & Shared-Library Design Findings

Independent zero-trust audit of petproject at `f242158265163d65ef73d378331d2bd7a92479ee`.
Scope: `gateway-service` (48 files + resources), all 8 utils modules (106 main Java files),
root/utils/gateway poms, `k8s/*.yaml` gateway/ingress parts. Every file in scope was read;
the only file I could not meaningfully review is noted in the coverage section at the end.

Confidence legend:
- CONFIRMED_FROM_SOURCE — the cited code/config lines were read and the statement follows
  from them directly (runtime behavior of a build I am not allowed to run may still be cited
  separately as inference).
- HIGH_CONFIDENCE_INFERENCE — follows from source with high probability; a framework
  default or dependency behavior I could not execute.
- NEEDS_RUNTIME_VERIFICATION — plausible from source but requires running the system.

Severity: P0 = must fix before any production traffic; P1 = fix before launch;
P2 = fix in normal cadence; P3 = hygiene/documentation.

---

## ARCH-1 — Production k8s gateway runs with `trusted-proxy.hops=0`, collapsing per-client rate limiting and silently disabling the admin IP allowlist

- **Severity:** P1
- **Confidence:** CONFIRMED_FROM_SOURCE (config facts; runtime collapse is a direct consequence)
- **Affected module:** gateway-service, k8s deployment config
- **Files:**
  - `gateway-service/src/main/resources/application.yml:65-67` — `gateway.trusted-proxy.cidrs` defaults to empty, `hops` to 0.
  - `k8s/gateway-service.yaml:32-42` — Deployment env sets only issuer/redis/CORS; **no** `GATEWAY_TRUSTED_PROXY_CIDRS`, `GATEWAY_TRUSTED_PROXY_HOPS`, or `ADMIN_IP_ALLOWLIST`.
  - `k8s/ingress.yaml:7` — nginx ingress in front of the gateway (adds `X-Forwarded-For`).
  - `gateway-service/.../filter/ClientIpResolver.java:23-32` — with `hops=0` or untrusted peer, the client IP is the **transport peer** (the ingress pod IP).
  - `gateway-service/.../config/GatewaySecurityPostureValidator.java:94-101` — this condition only **logs a warning** at startup.
  - Consumers of the resolved IP: `PreAuthRateLimitFilter.java:40-42`, `RateLimitFilter.java:63-67`, `RateLimitKeyResolver.java:40-46`, `AdminIpAllowlistFilter.java:61-68`.
- **Current behavior:** Behind the nginx ingress every external request arrives with the
  ingress pod as the peer address. Because hops=0 and the trusted CIDR list is empty, the
  gateway ignores `X-Forwarded-For` for metering and keys pre-auth/edge buckets by the
  ingress address. The admin IP allowlist is empty ⇒ inactive (`AdminIpAllowlistProperties.active()`,
  `AdminIpAllowlistProperties.java:15-17`); `gateway.admin-ip-allowlist.required=false`
  (`application.yml:103`).
- **Why problematic:** The platform ships sophisticated per-client token buckets (pre-auth
  3000 rpm, backoffice 10 rpm, search 60 rpm) and a fleet limiter with fair-share lanes —
  but in the only production-shaped deployment they all meter **one key** (the ingress IP).
  One noisy/compromised client behind NAT exhausts the shared pre-auth bucket and gets 429s
  for **all** users. The startup posture validator explicitly predicts this
  ("all clients share the address of the proxy") yet only warns.
- **Business/operational impact:** Availability: trivially triggerable global 429s.
  Security-in-depth: backoffice protection = JWT ADMIN role only; the IP allowlist safety
  net advertised by `GATEWAY_REQUIRE_ADMIN_IP_ALLOWLIST` is off in the shipped manifests.
- **Recommended fix:** In `k8s/gateway-service.yaml` set `GATEWAY_TRUSTED_PROXY_CIDRS` to the
  ingress controller pod/node CIDR and `GATEWAY_TRUSTED_PROXY_HOPS=1` (nginx overwrites
  XFF), and set `ADMIN_IP_ALLOWLIST` (+ `GATEWAY_REQUIRE_ADMIN_IP_ALLOWLIST=true`) for
  non-sandbox clusters. Change `GatewaySecurityPostureValidator.validateClientIpTrust()`
  to **throw** instead of warn when a reverse proxy is configured (ingress present) but hops=0 —
  at minimum in the `prod` profile. Pattern: Trusted Proxy Hop-Count Validation (already
  implemented, just warn-only).
- **Trade-offs:** hop-count trust requires the ingress to overwrite (not append to) XFF;
  nginx `set_real_ip_from`/proxy behavior must match. If the ingress can't be pinned to a
  CIDR, accept the warning but document that per-client limits degrade to per-proxy.
- **Regression tests:** existing `ClientIpResolverTest`-style tests extended for
  hops=1 + trusted ingress CIDR; a posture-validator test asserting startup failure in prod
  profile with hops=0; k8s manifest lint asserting the env keys exist for prod overlays.

## ARCH-2 — `resilience4j-bulkhead` declared without version and unmanaged by any BOM in the chain; last packaged jar shows a 2.3.0/2.4.0 split resilience4j fleet

- **Severity:** P1 (build reproducibility) with P2 split-version consequence
- **Confidence:** CONFIRMED_FROM_SOURCE for the pom/BOM facts and the jar listing
  (jar is a stale build from 2025-11-24, predating the current pom); NEEDS_RUNTIME_VERIFICATION
  for what the *current* pom resolves to on a clean machine.
- **Affected module:** gateway-service, root pom
- **Files:**
  - `gateway-service/pom.xml:56-59` (`:58` the artifact line) — direct dependency `io.github.resilience4j:resilience4j-bulkhead` with **no** `<version>`.
  - `pom.xml:157-171` — root `dependencyManagement` manages only `resilience4j-spring-boot4`, `resilience4j-reactor`, `resilience4j-micrometer` at `${resilience4j.version}`=2.4.0 (`pom.xml:48`).
  - Local-repo BOM checks (read-only): `spring-cloud-dependencies-2025.1.0.pom` — 0 `resilience4j` mentions; `spring-boot-dependencies-4.1.1.pom` — 0.
  - `gateway-service/target/gateway-service-1.0-SNAPSHOT.jar` `BOOT-INF/lib/`: `resilience4j-circuitbreaker-2.4.0`, `resilience4j-micrometer-2.4.0`, **`resilience4j-core-2.3.0`, `resilience4j-bulkhead-2.3.0`, `resilience4j-retry-2.3.0`, `resilience4j-ratelimiter-2.3.0`, `resilience4j-timelimiter-2.3.0`**.
- **Current behavior:** A versionless direct dependency that nothing manages either fails
  the Maven model build or resolves accidentally via transitive mediation. The one packaged
  artifact I could inspect resolved to a mixed 2.3.0/2.4.0 classpath: circuitbreaker 2.4.0
  running against core 2.3.0. `DownstreamResilienceFilter` uses `CircuitBreaker` +
  `BulkheadRegistry` from exactly these libraries (`resilience/DownstreamResilienceFilter.java:46-47`).
- **Why problematic:** The gateway's overload protection (its core resilience feature) is
  built on an unpinned dependency. Cross-minor resilience4j mixes have no compatibility
  guarantee; the build outcome depends on mediation order, not on declared intent.
- **Business/operational impact:** Non-reproducible builds; risk of
  `NoSuchMethodError`/behavior drift inside the breaker/bulkhead path under load — the
  worst possible place to discover it.
- **Recommended fix:** Import `io.github.resilience4j:resilience4j-bom:${resilience4j.version}`
  in the root `dependencyManagement` (or pin every gateway r4j artifact to
  `${resilience4j.version}`), then remove per-artifact versions from the gateway pom.
- **Trade-offs:** BOM import pins the whole r4j fleet; that is exactly what is wanted here.
- **Regression tests:** CI gate running `mvn dependency:tree` (or enforcer
  `requireSameVersions`/banned-dependency rules) asserting one resilience4j version per
  reactor build; rebuild the gateway jar and re-check `BOOT-INF/lib`.

## ARCH-3 — Gateway resilience has no downstream response timeout and the slow-call path of the circuit breaker is hard-disabled

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE (absence of config; hardcoded threshold) +
  NEEDS_RUNTIME_VERIFICATION for the Netty default (no response timeout ⇒ unbounded wait).
- **Affected module:** gateway-service
- **Files:**
  - `gateway-service/src/main/resources/application.yml` — no `spring.cloud.gateway.httpclient.*` keys at all (whole file), so no `response-timeout`, no connect timeout.
  - `gateway-service/.../resilience/DownstreamResilienceConfiguration.java:15,17-27` — `SLOW_CALL_RATE_THRESHOLD = 100f` hardcoded; `slowCallDurationThreshold` (2 s, `application.yml:97`) is therefore recorded but can never open the breaker (rate cannot exceed 100%).
  - `gateway-service/.../resilience/DownstreamResilienceFilter.java:82-95` — breaker records only 5xx as errors (`recordOutcome`) and exceptions (`recordFailure`).
- **Current behavior:** The circuit breaker opens only on exception/5xx storms. A downstream
  that hangs (accepts connections, never responds) produces neither: requests just stall
  (NEEDS_RUNTIME_VERIFICATION: Reactor Netty default response timeout is unbounded) and
  slow calls accumulate without tripping anything.
- **Why problematic:** The audit question "are timeouts configured" has the answer **no**
  for gateway→service hops, despite `gateway.resilience.circuit-breaker.slow-call-duration-threshold`
  suggesting otherwise. The config key is decorative: `CircuitBreakerConfig` with
  slowCallRateThreshold=100 never trips on slowness.
- **Business/operational impact:** Slow-failure scenarios (GC thrash, DB pool exhaustion in a
  service) hold gateway connections and memory; graceful degradation promised by the
  breaker does not engage until errors start surfacing.
- **Recommended fix:** Set `spring.cloud.gateway.httpclient.response-timeout` (e.g. 15 s)
  and `connect-timeout` (e.g. 3 s); make `slowCallRateThreshold` configurable and default
  to 50 so `slow-call-duration-threshold` has effect. Pattern: Bulkhead + Timeout +
  Circuit Breaker (Fowler/Release It!) — a breaker without a timeout is half a breaker.
- **Trade-offs:** Long uploads/downloads via the gateway (webhook payloads, media) may need
  per-route timeout overrides (`gateway.route-targets` already exists as an override point).
- **Regression tests:** integration test with a hanging stub server asserting 504/503 within
  the timeout; breaker test asserting OPEN after slow-call ratio exceeds 50% with
  responses 2xx-but-slow.

## ARCH-4 — `spring-boot-starter-aop` pinned to milestone `4.0.0-M2` overriding the Boot 4.1.1 GA management

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE (pom facts); runtime impact NEEDS_RUNTIME_VERIFICATION
- **Affected module:** root pom → common-logging → every service
- **Files:** `pom.xml:74` (`<spring-boot-starter-aop.version>4.0.0-M2</...>`, resolved at `:74` in current tree) and `pom.xml:197-201` (dependencyManagement entry); consumer `utils/common-logging/pom.xml` (`spring-boot-starter-aop`, compile scope); the aspects shipped fleet-wide: `common-logging/aspect/LoggerAspect.java`, `aspect/AuditAspect.java`.
- **Current behavior:** Every service resolves its AOP starter from the 4.0.0-M2 milestone
  line while the platform runs Boot 4.1.1 GA; `spring-boot-starter-aop/4.0.0-M2` exists in
  the local repository, so the pin has actually been resolved.
- **Why problematic:** Mixing a pre-GA starter (which transitively pins its own Spring
  Framework/AOP versions) with GA Boot 4.1.1 is undefined-behavior territory for the
  machinery every `@Transactional`/aspect depends on.
- **Business/operational impact:** Latent proxy/aspect failures or classpath drift after
  dependency upgrades; impossible to get vendor support for a mixed-line classpath.
- **Recommended fix:** Delete the property and the DM entry; let `spring-boot-starter-parent`
  4.1.1 manage it.
- **Trade-offs:** none identified — the pin looks like a leftover from a pre-GA migration.
- **Regression tests:** fleet build + `dependency:tree | grep spring-aop` gate asserting a
  single Spring Framework line; smoke test exercising `@Audited`/`@LogPerformance` aspects.

## ARCH-5 — Deprecated `JsonKafkaSerializer` is still the default wiring for the generic `kafkaTemplate`, with a silent `toString()` corruption fallback and an ignored injected ObjectMapper

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** utils/common-kafka (consumed by all kafka services)
- **Files:**
  - `utils/common-kafka/.../config/KafkaAutoConfiguration.java:29-43` — default `kafkaProducerFactory` puts `value.serializer = serializer.getClass().getName()` (class-name string) ⇒ Kafka instantiates `JsonKafkaSerializer` via the **no-arg constructor**; the `ObjectProvider<ObjectMapper>` supplied mapper is dead configuration.
  - `utils/common-kafka/.../serialization/JsonKafkaSerializer.java:10-37` — `@Deprecated(since="2026-09-02", forRemoval=true)`; no-arg constructor uses `new ObjectMapper()` (no JavaTimeModule, Boot defaults absent); `serialize` catches **all** exceptions and falls back to `data.toString().getBytes(...)`.
- **Current behavior:** Any producer using the generic `kafkaTemplate` bean with an object
  carrying `Instant`/`Duration` fields gets a Jackson serialization failure that is silently
  swallowed and replaced by the object's `toString()` output on the wire.
- **Why problematic:** Two compounding defects: (a) a forRemoval deprecated class is still
  load-bearing default wiring; (b) its failure mode is silent data corruption of the event
  stream, which consumers then route to DLT as poison records.
- **Business/operational impact:** Corrupted events are discoverable only downstream (DLT
  noise, consumer decode errors), not at the producing service.
- **Recommended fix:** Remove `JsonKafkaSerializer` from `KafkaAutoConfiguration` (the
  fleet's real producers are the outbox relays using `stringKafkaTemplate` with
  pre-serialized JSON — e.g. `order-service/.../OrderOutboxRelay.java:81` passes
  `e.getPayload()` strings); if the generic template must stay, make it
  `KafkaTemplate<String,String>` and fail fast on non-String payloads.
- **Trade-offs:** strictly removes a footgun; any service relying on the generic template
  must migrate to explicit JSON strings (grep shows outbox relays already are).
- **Regression tests:** serializer unit test asserting an exception (not `toString()`) on
  unmappable types; context test asserting the deprecated class is not referenced by any
  `@Bean`.

## ARCH-6 — `KeycloakTokenClient` / `KeycloakAdminClient` use the shared platform `RestClient.Builder` with no explicit timeouts; only the standalone fallback has them

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE (code paths) + NEEDS_RUNTIME_VERIFICATION for the
  effective default factory timeouts Boot picks when only the platform builder exists.
- **Affected module:** utils/common-keycloak (auth-service login/admin paths)
- **Files:**
  - `utils/common-keycloak/.../config/KeycloakAutoConfiguration.java:65-77` — `platformRestClient(...)` returns `platformBuilder.clone()` **without** any request factory/timeouts; only the `platformBuilder == null` fallback applies 5 s connect/read (`standaloneRestClientBuilder`).
  - Contrast: `client/KeycloakServiceTokenProvider.java:185-205` — pooled factory with explicit connect/response/connection-request timeouts and `disableAutomaticRetries()`.
  - Users of the unbounded path: `KeycloakTokenClient.java:120-136` (`postToken` — password grant used for user login/verification), `KeycloakAdminClient.java:142-163,183-199` (admin REST).
- **Current behavior:** When Boot's auto-configured `RestClient.Builder` bean is present
  (the normal case), token/admin HTTP calls inherit whatever default factory Boot chose
  (historically JDK or Apache with no explicit response timeout).
- **Why problematic:** The login path (`POST /api/v1/auth/...` → `KeycloakTokenClient.login`)
  is a pre-auth gateway public endpoint; a hung Keycloak turns into hung servlet threads on
  auth-service — the exact failure `KeycloakServiceTokenProvider` was carefully hardened
  against for the service-token path.
- **Business/operational impact:** Thread-pool exhaustion and cascading unavailability of
  the auth service during Keycloak degradation; also `PasswordGrant` verification flows
  (`verifyCredentials`) do login+logout round-trips per check.
- **Recommended fix:** Apply the same pooled/timeout request factory to the platform builder
  in `KeycloakAutoConfiguration` (or construct `KeycloakTokenClient` from a dedicated builder
  like the service-token provider). Pattern: Client Timeout Budget per dependency.
- **Trade-offs:** none; timeouts belong at the client-factory layer regardless of builder origin.
- **Regression tests:** WireMock stub with delayed response asserting the call fails within
  the configured budget; assert both beans expose equivalent request-factory settings.

## ARCH-7 — The Checkstyle "quality gate" suppresses its structural rules for every module, leaving 4 regex rules fleet-wide

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** build/quality gate (root pom + config)
- **Files:**
  - `config/checkstyle-suppressions.xml:10-25` — per-module `<suppress>` entries covering **all 14 services and `utils/`** for `LineLength|FileTabCharacter|ImportOrder|UnusedImports|RedundantImport|MethodLength|ParameterNumber`.
  - `config/checkstyle.xml:9-57` — what remains enforceable fleet-wide: FileTabCharacter/LineLength (suppressed everywhere), UnusedImports (suppressed everywhere), two R1 `@Builder record` regexes, R3/R4 Kafka-key regexes.
  - `pom.xml:313-334,480-483` — checkstyle runs at `validate`, `failOnViolation=true`.
  - Context: commit `5d6ba251` replaced the `common-patterns` FleetPatternComplianceTest (R1–R6 harness, 315 lines) with this Checkstyle setup.
- **Current behavior:** `./mvnw validate` passes structurally broken code in any module:
  unused imports, 500-line methods, 20-parameter methods, tabs — all suppressed
  per-module-tree rather than per-known-offender.
- **Why problematic:** The suppression file suppresses whole module directories, not
  individual legacy files; the replacement for the removed pattern harness is therefore a
  gate that enforces almost nothing structural. (See also ARCH-17: the module it replaced is
  a ghost, and `README.md:612` still advertises it.)
- **Business/operational impact:** Structural drift is invisible to CI; review effort is the
  only line of defense; the docs overstate the gate's guarantees.
- **Recommended fix:** Keep legacy exceptions file-scoped (Checkstyle `suppress` supports
  exact files), re-enable the basic TreeWalker rules fleet-wide, or document honestly that
  only R1/R3/R4 regexes are enforced. Pattern: ratchet (no new suppressions, shrink existing).
- **Trade-offs:** short-term red builds while legacy files get file-scoped suppressions.
- **Regression tests:** CI test asserting no `<suppress>` entry covers a whole module
  directory; spot-fix one module and let the gate go green.

## ARCH-8 — Poison Kafka records are retried 3× before dead-lettering (`PoisonRecordException` is never classified non-retryable)

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** utils/common-kafka + every consumer service
- **Files:**
  - `utils/common-kafka/.../consumer/BaseKafkaConsumer.java:50-72` — undecodable payloads throw `PoisonRecordException` explicitly labeled as DLT-routing.
  - `utils/common-kafka/.../consumer/BaseKafkaListenerConfig.java:62-65` — `DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L))` with no `addNotRetryableExceptions(...)`.
  - Repo-wide grep: zero occurrences of `addNotRetryable|notRetryable`.
- **Current behavior:** A record that will *never* decode is re-consumed and re-failed three
  times (1 s apart) before `DeadLetterPublishingRecoverer` moves it to `<topic>.DLT`, triple-
  logging the raw payload (which may be large/adversarial) at ERROR each time.
- **Why problematic:** Deterministically-failing input should skip retries by construction;
  the class exists to express exactly that intent but is wired to the retrying path.
- **Business/operational impact:** Triples poison-record processing latency and DLT lag;
  one oversized garbage record delays a partition 3 s per occurrence; error-log noise.
- **Recommended fix:** `errorHandler.addNotRetryableExceptions(PoisonRecordException.class)`
  in `BaseKafkaListenerConfig.deadLetterErrorHandler` (and log the payload at WARN with
  truncation). Pattern: Retry Taxonomy (retryable vs. terminal failures).
- **Trade-offs:** none — undecodable bytes cannot become decodable on retry.
- **Regression tests:** consumer test asserting a corrupt record lands in DLT with exactly
  one delivery attempt; assert non-poison exceptions still retry 3×.

## ARCH-9 — No network segmentation in k8s: in-cluster callers bypass every gateway edge control

- **Severity:** P2
- **Confidence:** CONFIRMED_FROM_SOURCE (manifests) for the topology; exploitability requires cluster access (threat-model dependent).
- **Affected module:** k8s deployment (gateway + services)
- **Files:** `k8s/` contains no `NetworkPolicy` object (kinds in `k8s/*.yaml`: Namespace,
  Deployment, Service, Ingress, HPA, PDB, ServiceAccount, secrets template, alerts, dashboards);
  `k8s/microservices.yaml` — all Services ClusterIP; `gateway-service/.../constant/ServiceRoute.java`
  (edge role gate and rate limits exist only in the gateway process).
- **Current behavior:** Any pod in `shop-system` can call any service directly
  (`http://order-service:8084/api/v1/backoffice/...`), skipping the gateway's rate limiters,
  backoffice ADMIN edge gate and admin IP allowlist. Authz still holds: services re-validate
  JWTs (`common-security/config/BaseSecurityConfig.java:36-72`) and backoffice/internal
  controllers carry `@PreAuthorize` (e.g. `product-service/.../BackofficeProductController.java`,
  `inventory-service/.../InternalInventoryController.java:24 hasRole('SERVICE')`) — verified
  by grep: every backoffice controller has at least one `@PreAuthorize`.
- **Why problematic:** The gateway's edge posture is presented as protection, but three of
  its four controls (rate limits, ADMIN edge gate, IP allowlist) evaporate for in-cluster
  traffic. A single compromised low-privilege pod gets unthrottled access to every endpoint
  its token's azp allows — and public azp (`ecommerce-client`) is accepted by most services
  (`application-shared.yml:101-137`).
- **Business/operational impact:** Lateral-movement blast radius; unthrottled in-cluster
  request floods (no rate limit) against services directly.
- **Recommended fix:** Default-deny NetworkPolicy in `shop-system` allowing ingress only
  from gateway/ingress pods to services (plus service→service for the documented sync
  clients: order→inventory/product/tax/promotion/payment, product→media, search→product,
  media/product→keycloak, all→keycloak/redis/kafka/postgres). Pattern: zero-trust segmentation
  at the mesh/policy layer to match the app-layer model.
- **Trade-offs:** policy churn when adding a sync client; pair with the
  `KafkaTopicContract`/`application-shared.yml` allowlists as the source of truth.
- **Regression tests:** conftest/kyverno policy asserting a default-deny NetworkPolicy
  exists; integration test that a direct pod-to-service call with a user token is accepted
  (documented behavior) while cluster-external access stays gateway-only.

## ARCH-10 — `AdminIpAllowlistFilter` and `GlobalRateLimitFilter` share `Ordered.HIGHEST_PRECEDENCE`; their relative order is undefined

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** gateway-service
- **Files:** `gateway-service/.../filter/FilterOrder.java:7-13` (`ADMIN_IP_ALLOWLIST = HIGHEST_PRECEDENCE`); `gateway-service/.../ratelimit/GlobalRateLimitFilter.java:50-52` (also `HIGHEST_PRECEDENCE`); `RateLimitFilter` uses +10, `AdminRoleGateFilter` +20, `DownstreamResilienceFilter` +30.
- **Current behavior:** Both are `GlobalFilter`s with the identical order value; the
  AnnotationAwareOrderComparator keeps insertion order for ties (bean-registration order),
  which is stable per build but is an implementation accident, not a contract. If the
  rate limiter runs first, requests that the IP allowlist is about to reject still consume
  fleet tokens; if the allowlist runs first, rejected IPs never touch Redis.
- **Why problematic:** Ordering between a security control and a metering control should be
  explicit; `FilterOrder` exists precisely to make this explicit and then misses these two.
- **Business/operational impact:** Minor: wasted Redis capacity and misleading
  fleet-limiter metrics when the allowlist is active; potential surprise after refactors.
- **Recommended fix:** Give the allowlist `HIGHEST_PRECEDENCE` and the fleet limiter
  `HIGHEST_PRECEDENCE + 5` (documenting security-before-metering), or vice versa if the
  intent is to meter blocked scans too.
- **Trade-offs:** none.
- **Regression tests:** assertion test reading both `getOrder()` values and enforcing
  strict inequality (the repo has similar meta-tests for filters).

## ARCH-11 — `common-core` is a shared kernel carrying every bounded context's error vocabulary

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** utils/common-core (all services depend on it via common-spring)
- **Files:** `utils/common-core/.../exception/ErrorCode.java:24-135` — AUTH-*, AUD-*, PRD-*,
  INV-*, ORD-*, PAY-*, FAV-*, PRO-*, TAX-*, NTF-*, SHP-*, RTG-*, SRH-*, MED-* codes in one
  enum; every service compiles against all 14 contexts' vocabulary.
- **Current behavior:** Adding `PAYMENT_*` code X recompiles/redeploys all 14 services;
  payment vocabulary is importable by inventory code without review friction.
- **Why problematic:** Classic shared-kernel coupling: the contract surface between services
  is a single enum, so the kernel can only grow and never shrink; context-specific renames
  are global events. (HTTP-mapped error semantics themselves are fine and consistently used
  by `ApiExceptionHandler`.)
- **Business/operational impact:** Slow, riskier cross-cutting changes; error-code
  namespace collisions as services grow; temptation to reuse another context's code
  ("ORD-4007" thrown from payment).
- **Recommended fix:** Keep shared transport codes (ERR-04xx/05xx + AUD) in common-core;
  move context prefixes into per-service `ErrorCatalog` implementations (the interface
  `common-core/exception/ErrorCatalog.java:5-11` already supports this) aggregated at the
  gateway error-writer level for edge responses.
- **Trade-offs:** gateway no longer understands every service code string (it only needs
  the four it maps: `GatewayErrorResponseWriter.java:22-26` — already a bounded set).
- **Regression tests:** existing `ErrorCode` uniqueness/format test migrated per service;
  contract test that edge responses keep ERR-04xx codes.

## ARCH-12 — `common-core` drags the JPA stack into every consumer; the gateway un-drags it by hand

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** utils/common-core, gateway-service
- **Files:** `utils/common-core/pom.xml` — compile-scope `spring-boot-starter-data-jpa` and `jakarta.persistence-api`; consumers `AbstractMappedEntity.java`, `SoftDeletable.java` (the only JPA-touching classes); `gateway-service/pom.xml:28-37` — explicit `<exclusions>` of both artifacts.
- **Current behavior:** The "core" utility module pins the persistence stack on every
  consumer; the only reactive service must remember to exclude two artifacts or webflux
  and servlet stacks collide.
- **Why problematic:** Dependency direction is inverted: `common-core` (conceptually the
  innermost ring) depends on a heavyweight adapter technology. Any future non-JVM-persistent
  service repeats the exclusion ritual; JPA version bumps leak to services that never touch
  a database (search).
- **Business/operational impact:** Minor build-weight/bloat; recurring footgun for new services.
- **Recommended fix:** Extract `common-jpa` (or move `AbstractMappedEntity`/`SoftDeletable`
  into a `common-spring-jpa` fragment) and drop JPA from common-core; keep
  `EntityCreateUpdateMapper`/`BaseMapper` where they are. Pattern: Ports & Adapters —
  persistence is an adapter, not core.
- **Trade-offs:** one more module; services add one dependency line.
- **Regression tests:** gateway dependency tree asserts no `jakarta.persistence`; services
  unchanged.

## ARCH-13 — `KafkaMessagePublisher.publish(topic, key, Object value)` silently `toString()`s non-String payloads

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE (API shape); impact HIGH_CONFIDENCE_INFERENCE
- **Affected module:** utils/common-kafka
- **Files:** `utils/common-kafka/.../producer/KafkaMessagePublisher.java:54-67,69-76,90` — `(value == null) ? null : value.toString()` in `publish`, `publishAsync`, `publishBatch`.
- **Current behavior:** The publisher's contract ("value must be pre-serialized JSON",
  required by `KafkaTemplate<String,String>`) is enforced nowhere. A caller passing an
  entity or record publishes Lombok `toString()` output as the event. Current in-repo
  callers pass strings (outbox `payload` columns; tests pass `objectMapper.writeValueAsString`),
  so nothing is corrupted **today** — this is a trap, not an active bug.
- **Why problematic:** Type-unsafe API adjacent to an at-least-once event bus; the failure
  is silent and lands on consumers.
- **Business/operational impact:** One careless call site corrupts a contracted topic
  (`KafkaTopicContract` validates topic names, not payload shapes).
- **Recommended fix:** Change signatures to `String value` (or add `publishJson(topic, key,
  Object, ObjectMapper)` that serializes explicitly). Pattern: Make illegal states
  unrepresentable.
- **Trade-offs:** compile fixes at call sites — all current call sites already pass Strings.
- **Regression tests:** compile-level; existing publisher tests keep passing.

## ARCH-14 — `DateTimeUtils` formatter cache: check-then-clear race and whole-cache eviction (static mutable state)

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE (race is benign — flagged per the static-mutable-state
  audit question; no incorrect formatting can result)
- **Affected module:** utils/common-core
- **Files:** `utils/common-core/.../util/DateTimeUtils.java:13,27-30` — static `ConcurrentHashMap`; `if (!containsKey && size >= MAX) clear()` then `computeIfAbsent`.
- **Current behavior:** Two threads may both clear or one may clear between check and
  insert; a full cache wipes **all** entries, not the oldest. Worst case is redundant
  `DateTimeFormatter.ofPattern` construction (which is thread-safe and idempotent).
- **Why problematic:** Canonical example of avoidable static mutable state; the clear is a
  latency spike and a code smell, not a correctness bug (do not over-fix).
- **Business/operational impact:** Negligible in practice; unbounded-pattern callers could
  evict hot entries repeatedly.
- **Recommended fix:** If patterns are a fixed set, drop the size guard entirely; otherwise
  use a proper bounded cache (Caffeine) or `ConcurrentHashMap` without clearing.
- **Trade-offs:** none.
- **Regression tests:** concurrent format test with >100 distinct patterns asserting
  correctness (already implicitly covered by formatter immutability).

## ARCH-15 — Fleet config defaults: `spring.profiles.active: dev` baked into the shared document and `percentiles-histogram.all: true`

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE (config); Prometheus impact HIGH_CONFIDENCE_INFERENCE
- **Affected module:** utils/common-config (imported by every service)
- **Files:** `utils/common-config/src/main/resources/application-shared.yml:2-4` (`profiles.active: dev`), `:45-49` (`metrics.distribution.percentiles-histogram.http.server.requests: true`, `all: true`).
- **Current behavior:** A bare `java -jar service.jar` without env overrides boots in the
  `dev` profile; every metric (JVM, Kafka client, Hikari, …) gets percentile histograms
  enabled, multiplying time series ~10× per metric.
- **Why problematic:** The shared document is the single configuration source by design
  ("fleet hardening cannot be shadowed"); shipping a dev default and a cardinality-maxed
  metrics default inside it works against production hygiene. k8s/compose set
  `SPRING_PROFILES_ACTIVE` explicitly, so the deployed risk is limited to bare-metal runs.
- **Business/operational impact:** Prometheus storage blow-up at fleet scale; surprise dev
  profile behavior in ad-hoc runs.
- **Recommended fix:** Remove `spring.profiles.active` from the shared doc (let SPRING_PROFILES_ACTIVE
  rule), scope histogram `all: true` to a perf profile or drop it in favor of the explicit
  `http.server.requests` entry.
- **Trade-offs:** dashboards relying on histogram-derived p95/p99 for all metrics need
  re-checking.
- **Regression tests:** config-documentation test asserting no `profiles.active` key in
  `application-shared.yml`.

## ARCH-16 — `aud` claim is never validated fleet-wide (azp-only trust model) — deliberate, but undocumented

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE (configuration facts)
- **Affected module:** gateway-service + utils/common-security + shared config
- **Files:** `gateway-service/.../config/GatewayJwtSecurityConfig.java:50-59` (aud validator only when `expectedAudiences` non-empty; `GatewayJwtProperties.java:15` defaults empty; `application.yml:35-36` sets only `expected-client-ids`); `utils/common-security/.../config/BaseSecurityConfig.java:89-97` + `application-shared.yml:95` (`expected-audiences` default empty).
- **Current behavior:** Edge and services validate `iss`, `exp/nbf`, `azp` strictly; the
  `aud` claim is ignored. With Keycloak issuing `aud=account` (or the client id) and strict
  azp allow-listing, this is a **sound** single-tenant model — the azp check subsumes the
  audience check for first-party clients.
- **Why problematic:** Only a finding because it is invisible: nothing in docs/config
  states that azp is the trust anchor, so a future multi-client setup (third-party client,
  token exchange) can silently inherit "aud doesn't matter".
- **Business/operational impact:** None today; latent misconfiguration risk.
- **Recommended fix:** Document the azp-centric model next to `GatewayJwtProperties` and set
  `expected-audiences` when the platform ever accepts a second client.
- **Trade-offs:** none.
- **Regression tests:** validator unit tests exist (`GatewayJwtSecurityConfigTest` in
  target/surefire-reports); add one asserting empty-audience config skips aud (pinning
  intended behavior).

## ARCH-17 — `utils/common-patterns` ghost module: untracked build residue contradicts repository documentation

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** utils (build hygiene), docs
- **Files/facts:** `utils/common-patterns/.flattened-pom.xml` + `utils/common-patterns/target/**` are the only files on disk; `git ls-files utils/common-patterns/` → empty; deletion commit `5d6ba251` removed pom + harness; `utils/pom.xml:18-27` has no such module; **`README.md:612` still advertises "common-patterns: Architectural verification suites"**; a plan doc still instructs running `./mvnw -pl utils/common-patterns test`, which cannot succeed.
- **Current behavior:** Developers reading the README believe a pattern-compliance harness
  exists and runs; actually its replacement (Checkstyle) enforces almost nothing structural
  (ARCH-7).
- **Why problematic:** Documentation describes tooling that does not exist; untracked build
  residue (`target/`, `.flattened-pom.xml`) confuses audits (including this one's baseline).
- **Business/operational impact:** Misleading onboarding; false confidence in R1–R6 enforcement.
- **Recommended fix:** Delete the directory (it is untracked residue); update `README.md:612`
  to describe the real gate; either restore a real pattern harness or state plainly that
  Checkstyle enforces R1/R3/R4 only.
- **Trade-offs:** none.
- **Regression tests:** none needed (docs).

## ARCH-18 — SCG RedisRateLimiter outage detection relies on the negative-remaining header convention

- **Severity:** P3
- **Confidence:** NEEDS_RUNTIME_VERIFICATION
- **Affected module:** gateway-service
- **Files:** `gateway-service/.../ratelimit/RateLimitOutageGuard.java:21-24` — `isOutage` = `remaining` header starts with `"-"`; used by `RouteRateLimitFilter.java:52` for the SCG `RedisRateLimiter` (`RateLimitConfiguration.java:17-24`).
- **Current behavior:** The custom edge/fleet limiters signal outage explicitly
  (`EdgeRateLimitDecision.outage()`, `FleetRateLimitDecision.outage()` — robust). The
  Spring-Cloud-Gateway limiter path instead infers Redis outage from negative header values,
  which matches SCG's implementation convention (rate limiter returns "allowed" with -1
  headers on failure) but is undocumented behavior coupling to a third-party internal.
- **Why problematic:** An SCG upgrade that changes failure signaling silently flips the
  outage policy from FAIL_CLOSED to allow-through (headers absent ⇒ `isOutage=false` ⇒
  `response.isAllowed()` decides — which in SCG's degraded mode is `true`).
- **Business/operational impact:** Over-limit traffic passes during a Redis outage if the
  convention breaks; config default is FAIL_CLOSED (`RateLimitProperties.java:15`), so the
  intent is explicit.
- **Recommended fix:** Wrap `rateLimiter.isAllowed` with `onErrorResume` mapping exceptions
  to an explicit outage decision (same as the two custom limiters), keeping the header check
  as a backstop.
- **Trade-offs:** small code addition; unifies three limiters on one outage contract.
- **Regression tests:** stub a RateLimiter returning allowed-with-negative-headers and one
  throwing; assert both map to outage handling.

## ARCH-19 — `StorageException` bypasses the shared error mapping (plain RuntimeException ⇒ 500)

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE for the hierarchy; service-level masking
  HIGH_CONFIDENCE_INFERENCE (service impls out of this run's scope)
- **Affected module:** utils/common-storage
- **Files:** `utils/common-storage/.../exception/StorageException.java:3-12` (extends `RuntimeException`, no `ErrorCatalog`); `utils/common-spring/.../web/exception/ApiExceptionHandler.java:256-268` (only `BusinessException` and framework types are mapped; everything else → 500 `ERR-0500`); `ErrorCode.MEDIA_STORAGE_UNAVAILABLE` (`ErrorCode.java:134`, 503) exists but is not reachable from the storage layer itself.
- **Current behavior:** S3 outages surfaced straight from `S3ObjectStorageService` map to
  HTTP 500 "internal server error" via the generic handler unless each service catches and
  re-wraps into `BusinessException`.
- **Why problematic:** Infrastructure unavailability is a 503-class condition (retryable,
  alertable); 500s trigger different on-call responses and break client retry heuristics.
- **Business/operational impact:** Media upload/download failures during MinIO/S3 blips
  report the wrong class of failure to clients and dashboards.
- **Recommended fix:** Make `StorageException` carry an `ErrorCatalog` (default
  `MEDIA_STORAGE_UNAVAILABLE`) and add a handler mapping, mirroring
  `KafkaPublishException` usage of explicit wrapping in publishers.
- **Trade-offs:** services that already map it per-endpoint must be checked for double mapping.
- **Regression tests:** MockMvc test with a storage stub throwing `StorageException`
  asserting 503 + stable code.

## ARCH-20 — Internal API surface is disclosed through publicly exposed api-docs

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE
- **Affected module:** gateway-service (route/auth posture), utils/common-core (path constants)
- **Files:** `gateway-service/src/main/resources/application.yml:44` (`/v3/api-docs/**` public); `utils/common-core/.../constants/ApiPaths.java:33-34` (`/internal/products/media-references`, `/internal/inventory/reservations`); `ServiceRoute.java:8-40` (no internal routes — correct).
- **Current behavior:** The gateway deliberately does not route `/api/v1/internal/**`
  (good), but the services' OpenAPI documents — publicly fetchable through the gateway —
  describe the internal endpoints, their parameters and their `@PreAuthorize` roles.
- **Why problematic:** Free reconnaissance for anyone targeting the cluster; also conflates
  the public contract with internal one.
- **Business/operational impact:** Information disclosure only (endpoints still require
  service-role JWTs).
- **Recommended fix:** Exclude `/internal/**` from springdoc per service
  (`springdoc.paths-to-match`) or serve api-docs only to authenticated/backoffice paths at
  the gateway.
- **Trade-offs:** slightly less convenient internal API exploration.
- **Regression tests:** gateway integration test asserting fetched api-docs JSON contains no
  `/internal/` paths.

## ARCH-21 — Inter-service `RestClient`s propagate correlation IDs but the shared builder layers are inconsistent about traceparent and timeouts

- **Severity:** P3
- **Confidence:** CONFIRMED_FROM_SOURCE for order-service wiring; fleet-wide applicability HIGH_CONFIDENCE_INFERENCE
- **Affected module:** order-service config (in-scope as the consumer of the shared tracing stack)
- **Files:** `order-service/.../config/RestClientConfig.java:54-66` — `baseRestClient` adds only `X-Correlation-Id` from MDC; the `TraceparentInterceptor` (which `TracingAutoConfiguration.java:51-84` attaches to `RestClient.Builder` beans) is NOT on these clients because they override the factory with a **locally built prototype builder** (`RestClientConfig.java:87-91` `RestClient.builder()`, not the Boot-managed builder that the BeanPostProcessor customizes); W3C propagation does happen for Kafka (`KafkaMessagePublisher.java:142-149`) and Keycloak calls (`KeycloakAutoConfiguration.java:79-86`).
- **Current behavior:** order-service → product/inventory/tax/promotion/payment HTTP calls
  carry `X-Correlation-Id` but no `traceparent` header ⇒ distributed traces break at the
  first sync hop out of order-service, while Kafka hops do propagate.
- **Why problematic:** Half the propagation strategy is wired; spans from sync saga steps
  (reserve, charge, tax) will be parentless in the tracing backend, defeating the
  OTel investment exactly on the checkout path.
- **Business/operational impact:** Debugging checkout incidents requires log correlation
  (correlation id exists) but not trace waterfall.
- **Recommended fix:** Add `TraceparentInterceptor` (or the OTel `RestClientCustomizer`) in
  `baseRestClient` next to the correlation header; audit other services' client configs the
  same way.
- **Trade-offs:** none.
- **Regression tests:** client test asserting outbound request contains `traceparent` while
  a span is active.

---

## Patterns verified as correctly implemented (do not "fix")

- **Outbox + claim lease + per-key ordering** (`common-kafka/producer/OutboxPublishLedger.java`,
  `OutboxBatchOrdering.java`; consumer `order-service/.../OrderOutboxRelay.java:62-175`):
  claim → batch publish with per-record outcomes → ordering-preserving marking
  (`markableAsSent` blocks later events of a failed key) → retry cap → stale-lease
  reclamation. Correct at-least-once design; per-event transactions serialize claims by id.
- **Atomic Redis token buckets**: edge (`RedisEdgeRateLimiter.java:21-49`) and two-tier
  fleet/client (`RedisFleetRateLimiter.java:21-58`) both run single Lua scripts over
  hash-tagged keys (same cluster slot ⇒ atomic), use Redis `TIME` (immune to app clock
  skew), and encode explicit outage outcomes. Lane assignment via stable
  `String.hashCode` (`FleetRateLimitBudget.java:42-44`) keeps a client on one lane across
  replicas. Lane/starvation guard validated at bind time
  (`GlobalRateLimitProperties.java:20-30`).
- **Anti-evasion path guards**: percent-encoding and matrix-variable rejection before role
  gate/scope matching (`AdminRoleGateFilter.java:40-53`, `RateLimitFilter.java:53-61`),
  multi-pass bounded decode (`RequestPathGuard.java:30-45`), strict IP-literal
  canonicalization defeating octal/hex shortcuts (`LiteralIpAddress.java`) — metering
  happens even for rejected evasive paths (`RateLimitFilter.java:82-87`).
- **Lazy JWT decoders** (reactive `config/LazyReactiveJwtDecoder.java:26-46`, servlet
  `common-security/config/LazyJwtDecoder.java:26-66`): CAS-single-build, servlet variant
  prewarms on a daemon thread and retries synchronously — startup survives Keycloak absence.
- **Startup posture validators**: CORS wildcard+credentials hard-fail, forward-headers
  strategy must be `none`, hops-without-CIDRs hard-fail
  (`GatewaySecurityPostureValidator.java:44-101`, `ClientIpTrustPostureValidator.java:27-48`).
- **Service token provider**: lock + expiry skew + failure cooldown + budgeted backoff +
  pooled client with timeouts and disabled auto-retries
  (`KeycloakServiceTokenProvider.java:83-169,185-205`).
- **Bounded async audit writer**: bounded queue, drop-with-counter on overflow, rate-limited
  sink-failure warnings, graceful shutdown (`BoundedAsyncAuditEventWriter.java`).
- **DLT replay with topic contract**: replay only to contracted topics, per-record commit,
  pause-and-seek on failure, consecutive-failure circuit
  (`DeadLetterReplayService.java:51-125`, `KafkaTopicContract`).
- **Trusted-proxy hop resolution (servlet side)**: rightmost-trusted walk with IPv4-mapped
  IPv6 handling (`common-spring/web/util/TrustedProxyPolicy.java:69-95,118-229`).
- **Facade/Adapter**: `ObjectStorageService` port + `S3ObjectStorageService` adapter with
  bounded in-memory download (`S3ObjectStorageService.java:118-147`) — correct ports &
  adapters shape.
- **Saga/Compensation**: present in order-service (`OrderCreateSaga`,
  `OrderCommitCoordinator`, `CompensationFailureService`, `OrderReconciliationScheduler`)
  — names and invocation sites verified; deep semantic audit of saga internals is outside
  this run's module scope (gateway + utils).

## DDD quality assessment (scope-honest)

Within the audited modules: the shared layer is deliberately **infrastructure-oriented**, not
domain-oriented — `ApiResponse`/`PageResponse` view models, MapStruct/ModelMapper mappers,
anemic `AbstractMappedEntity`/`SoftDeletable` bases, enum catalogs (`ErrorCode`,
`OutboxStatus`, `KafkaTopicContract` in common-kafka). That is an acceptable shared-kernel choice; the domain
models themselves live in the services (out of scope here). Evidence of real bounded-context
discipline at the boundaries: zero cross-service imports, per-service databases, per-service
error-code namespaces (albeit centrally declared, ARCH-11), an explicit Kafka contract with
producer ownership per topic (`KafkaTopicContract.java:50-82`), and per-service azp
allowlists as the integration ACL (`application-shared.yml:96-137`). Verdict: the
*boundaries* are real; the *shared kernel* (common-core) is the main DDD-style coupling
debt.

## Severity summary

**P0: 0 · P1: 2 (ARCH-1, ARCH-2) · P2: 7 (ARCH-3..9) · P3: 12 (ARCH-10..21) — total 21 findings.**

## Coverage statement

- Read completely: 48/48 gateway main Java files + 2 gateway resources; 106/106 utils main
  Java files + all 6 AutoConfiguration.imports + `application-shared.yml`; root/utils/gateway
  poms; all utils module poms; `k8s/gateway-service.yaml`, `k8s/ingress.yaml`, plus targeted
  reads of `k8s/microservices.yaml` (env/Service sections) and `config/checkstyle*.xml`.
- Supporting (out-of-scope, read only where a shared-library contract required a consumer):
  `order-service` outbox relay, RestClient config, one client; `@PreAuthorize` grep across
  all services; `docker-compose.yml` datasource/kafka lines; git history for
  `common-patterns`, gateway pom, root pom (read-only).
- Could not read / could not verify: nothing in scope was unreadable. Runtime behavior of
  Maven resolution and SCG/Netty defaults is marked NEEDS_RUNTIME_VERIFICATION in the
  findings above; no builds, containers, or state-changing git commands were run.
- Regeneration note: this file was rewritten verbatim from the completed analysis after an
  external wipe of the run directory (second occurrence); citations re-verified against HEAD
  `def2ffd8`, which differs from the audited `f2421582` only in audit-state files and one
  order-service test file — all app-source line citations remain valid.
