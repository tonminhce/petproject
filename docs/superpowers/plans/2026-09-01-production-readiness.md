# Production Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** production-grade cross-cutting hardening: backoffice edge routing (ADMIN,
ingress-only), per-service Keycloak clients, W3C trace propagation, gateway rate
limiting + IP allowlist, service-layer audit logging.

**Spec (binding):** docs/superpowers/specs/2026-09-01-production-readiness-design.md

**Tech:** Micrometer Tracing (OTel bridge, Boot BOM), Bucket4j (new gateway dep),
Keycloak realm import, common-* modules. **Zero business-logic changes in services.**

## Global constraints

- W1 (T1-T6) merges as one branch lane; W2 (T7) small per-service touches only.
- Edge filter order: IP → rate → role → route (spec D1). No audit in gateway (D6).
- Env ABSENT = allowlist INACTIVE (D4/D5 semantics — dev base compose unaffected).
- Both compose files `config -q` exit 0 after every compose touch.
- No new @Cacheable anywhere. ErrorCode: NO new codes unless plan-time check shows
  the ERR-04xx family lacks a 429/403 entry — then append ERR-0429-R ("err.rate_limited",429)
  + ERR-0403-A ("err.ip_blocked",403) as the family tail with i18n EN+VI (verify first).
- Every task: module tests green + affected fleet compile.

---

### Task 1: realm import — 3 service clients (W1)

**Files:** docker/keycloak/import/ecommerce-realm.json (+ docs/keycloak note if exists)
- Add confidential clients `order-service`, `rating-service`, `search-service`:
  serviceAccountsEnabled, client_credentials only, secret = current compose values
  (grep compose for *_SERVICE_CLIENT_ID/SECRET — reuse exact dev secrets), direct
  access grants off, standard flow off.
- Verify: `docker compose config -q` unaffected; JSON parses (`jq .` or python json).
- Test: Task 8's IT consumes this file — correctness proven there.

- [ ] **Step 1:** edit + JSON validation. **Step 2: commit** `feat(keycloak): service clients for order/rating/search (client_credentials)`

### Task 2: common-spring — OTel HTTP propagation (W1)

**Files:** utils/common-spring pom + autoconfigure + new RestClient interceptor
- Add micrometer-tracing-bridge-otel + OTLP exporter deps (Boot BOM, no versions).
- Auto-config registers tracer + W3C propagation; outbound interceptor injects
  traceparent on every fleet RestClient (where the ServiceTokenProvider interceptor
  already lives — same config site).
- OTLP exporter env-gated: OTEL_EXPORTER_OTLP_ENDPOINT absent → exporter bean not
  created; MDC still gets traceId/spanId. **N3: pin the env-gating with an explicit
  conditional-bean-creation test (context without env → no exporter bean + tracer
  present; context with env → exporter bean present) — do NOT rely on Boot
  auto-config behavior being obvious.**
- Test: auto-config context test (tracer present, no exporter when env absent);
  interceptor unit test asserting header injection when span active.

- [ ] TDD → implement → module green → **commit** `feat(common-spring): W3C traceparent propagation (HTTP + env-gated OTLP)`

### Task 3: common-kafka — Kafka trace headers (W1)

**Files:** utils/common-kafka producer wrapper + BaseKafkaListenerConfig/consumer
- Producer: inject `traceparent` record header (current span context).
- Consumer: extract header → continue span (micrometer Tracing.propagation via
  KafkaTelemetry or manual extractor — mirror whatever the bridge offers; NO new
  topic/format changes; headers are additive).
- Test: producer test asserts header present; consumer test asserts extraction
  (embedded/parallel to existing consumer tests).

- [ ] TDD → implement → module + one consumer-module spot check → **commit** `feat(common-kafka): traceparent record headers (producer + consumer)`

### Task 4: common-logging — @Audited + aspect (W1)

**Files:** utils/common-logging: annotation + aspect + AuditEventWriter + props
- `@Audited(action, resourceType)` on controller methods; aspect resolves actor
  (Authentication → sub/clientId ONLY), resourceId (path variable named `id`/`*Id`),
  outcome (success/fail), correlationId+traceId from MDC.
- Writer: one JSON line per event → file at `AUDIT_LOG_PATH` env (absent → stdout
  logger `AUDIT`), **N1: bounded pool corePoolSize=2 / maxPoolSize=4 /
  queueCapacity=1000, rejection = discard + WARN counter (audit NEVER blocks the
  request — queue overflow is logged, not propagated)**, failures log-and-continue.
- Payload EXACT per spec D6 (PII-safe). No annotation on GET endpoints.
- Test: aspect unit tests (actor extraction, PII exclusion — email/name never
  serialized), writer file/stdout modes, failure-tolerance test.

- [ ] TDD → implement → **commit** `feat(common-logging): @Audited annotation + structured JSON audit sink`

### Task 5: gateway — routes, rate limit, IP allowlist (W1; largest task)

**Files:** gateway-service: pom (+bucket4j), ServiceRoute/RouteConfig (+9 backoffice
routes mapping to existing service URIs), new AdminIpAllowlistFilter, RateLimitFilter,
AdminRoleGateFilter, filter ordering config, error envelope writer (fleet ApiResponse),
tests.
- 9 backoffice routes per ApiPaths prefixes → same target URIs as their service's
  existing route. Role gate: realm-role ADMIN claim required; 403 envelope otherwise.
- IP allowlist: ADMIN_IP_ALLOWLIST CIDR parsing; ABSENT=INACTIVE; bypass
  webhooks/actuator-health. Present+non-match → 403 envelope.
- Rate limit: bucket4j per-IP buckets for the two scopes (10/min, 60/min —
  configurable props); 429 envelope + X-RateLimit-Remaining header.
- Order: IP → rate → role → route — **N2: explicit getOrder() assignments:
  IP = HIGHEST_PRECEDENCE, rate = HIGHEST_PRECEDENCE+10, role =
  HIGHEST_PRECEDENCE+20 (ordered constants, not magic numbers)** (Spring cloud
  gateway global filters order).
- Tests: WebFluxRouteTests — matrix: no-token/401, user-token/403, admin-token/200
  (proxy to WireMock target), IP blocked, IP inactive-when-absent, webhook bypass,
  429 after burst + header, envelope shapes exact.

- [ ] TDD → implement → gateway suite green → **commit** `feat(gateway): backoffice edge routes (ADMIN) + rate limit + IP allowlist`

### Task 6: annotate 9 backoffice controllers (W2)

**Files:** the 9 Backoffice*Controller files (one commit)
- @Audited on every mutating endpoint (POST/PUT/DELETE/PATCH), action strings
  verb-style (`product.create`, `rating.hide`, `search.reindex`, …), resourceType
  stable nouns. GETs untouched. P2-6 storefront untouched.
- Tests: existing suites stay green; one slice test per module asserting the audit
  JSON line on one endpoint (spot matrix).

- [ ] Apply → all affected module tests green → **commit** `feat(*): @Audited on backoffice mutating endpoints (9 controllers)`

### Task 7: compose prod overlay + stability battery (W1)

**Files:** docker-compose.prod.yml (new, extends base), .env.prod.example
- Overlay: ingress ports ONLY on gateway (+keycloak admin if desired), ADMIN_IP_ALLOWLIST,
  OTEL endpoint, AUDIT_LOG_PATH volume, rotates service-client secrets via env.
  Internal network untouched (ingress-only rule). Base compose UNCHANGED.
- Verify: `docker compose -f docker-compose.yml config -q` AND
  `docker compose -f docker-compose.yml -f docker-compose.prod.yml config -q` both exit 0.
- Stability battery ×3 consecutive: `./mvnw -T1C install -DskipTests -q`; gateway,
  order, product, rating, search suites green each run (known order flake rule applies).

- [ ] Overlay + config checks + battery ×3 → **commit** `chore(compose): prod overlay — ingress-only, allowlist, otel, audit path`

### Task 8: realm import IT (W2)

**Files:** an IT in gateway or a small keycloak-config module test dir (Testcontainers
keycloak realm import)
- IT: start Keycloak testcontainer with the import file → assert 3 clients exist →
  request client_credentials token for each → 200 + token parses (sub=service account).
- Runs only where docker available (fleet IT convention).

- [ ] IT green → **commit** `test(keycloak): realm import IT — clients render + client_credentials grant`

### Task 9: final whole-branch review

- [ ] Reviewer: whole-branch diff; spec D1-D6 + §4 + §5 audit; ingress-only semantics
  proof (no internal call routed via edge); PII audit payload pin; filter order tests;
  both compose configs; stability battery rerun. Fix rounds until APPROVED.
