# OpenTelemetry Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** W3C traceparent + tracestate propagation across all HTTP inbound/outbound and Kafka producer/consumer boundaries in the fleet. NO agent/collector in this epic — instrumentation hooks only.

**Architecture:** Spring Boot's Micrometer Tracing OTel bridge auto-configures the OTel SDK. common-logging extracts/generates traceparent on inbound HTTP + populates MDC. common-logging RestClientConfig forwards traceparent outbound. common-kafka producer adds traceparent header; consumer wraps handle() in child Observation. Spec is binding authority: docs/superpowers/specs/2026-09-01-otel-propagation-design.md.

**Tech Stack:** Spring Boot 4 (fleet BOM), spring-boot-starter-actuator, io.micrometer:micrometer-tracing-bridge-otel (transitive), io.micrometer:micrometer-observation (transitive). NO additional runtime dependency.

**Spec:** docs/superpowers/specs/2026-09-01-otel-propagation-design.md

## Global Constraints

- common-* modules are W1 lane — single PR, all services pick up the change transitively.
- Per-service changes = yml additions only (management.tracing.sampling.probability env-driven). NO service-code changes required to benefit.
- Trace context: W3C 00 traceparent format, 32-hex traceId + 16-hex spanId. No baggage V1.
- Sampling: default 1.0 in common-spring; services override via env `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`.
- Correlation-Id stays alongside traceparent; MDC gains traceId + spanId.

---

### Task 1: common-logging — CorrelationIdFilter traceparent extraction

**Files:**
- Modify: utils/common-logging/.../filter/CorrelationIdFilter.java — add W3C `traceparent` extraction:
  - Inbound: read header `traceparent`; if present, parse (version 00, traceId 32 chars, spanId 16 chars, flags 2 chars). If parse fails or absent → generate new (Spring Boot's Tracer via ObservationRegistry handles; inject via Observation.start()).
  - Populate MDC: `MdcKey.TRACE_ID` + `MdcKey.SPAN_ID` (add to MdcKey enum if not present).
  - Response header: write `traceparent` back (echo inbound or new).
- Modify: utils/common-logging/.../constant/MdcKey.java — add TRACE_ID, SPAN_ID constants.
- Modify: utils/common-logging/.../config/LoggingPatternConfig.java or logback-spring.xml — add `[traceId=%X{traceId:-} spanId=%X{spanId:-}]` to log pattern.
- Test: CorrelationIdFilterTest (MockHttpServletRequest): inbound with traceparent → MDC populated; inbound without → generated; malformed traceparent → generated + WARN log.

- [ ] **Step 1: failing test** for traceparent extraction.
- [ ] **Step 2: implement.** Use io.micrometer.tracing.Tracer to obtain current span; if null, Observation.start("http.server").observe() opens one.
- [ ] **Step 3: run** ./mvnw -pl utils/common-logging test → GREEN.
- [ ] **Step 4: commit** feat(common-logging): CorrelationIdFilter extracts/generates W3C traceparent

### Task 2: common-logging — RestClientConfig outbound traceparent forwarder

**Files:**
- Modify: utils/common-logging/.../config/RestClientConfig.java (or wherever RequestHeadersInitializer lives — verify): add `RequestHeadersInitializer` that reads current Observation context via `Tracer.currentSpan()` and sets `traceparent` + `tracestate` headers on outbound request.
- Apply to ALL RestClient.Builder beans in fleet via the common-logging config (each service's RestClientConfig uses common's initializer).
- Test: RestClientConfigOutboundTest: mock Observation with known traceparent; build client; invoke; assert outbound request carries the header.

- [ ] **Step 1: failing test.**
- [ ] **Step 2: implement.** Use `tracer.currentSpan().context().traceId()` + `spanId()` to format W3C traceparent. If no current span (test scenario), skip header.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(common-logging): RestClientConfig forwards traceparent outbound

### Task 3: common-kafka — producer traceparent header

**Files:**
- Modify: utils/common-kafka/.../producer/BaseKafkaProducer.java (or wherever KafkaTemplate is wrapped) — add ProducerInterceptor (or in send() method) that adds `traceparent` + `tracestate` headers from current Tracer context.
- If producer is per-service, apply pattern via common-kafka's helper method `KafkaHeaders.withTraceContext(currentTracer)` that returns Header[] to add.
- Test: BaseKafkaProducerTest: send record → outbound headers contain traceparent when observation active; absent when no observation.

- [ ] **Step 1: failing test.**
- [ ] **Step 2: implement.** Helper method in common-kafka header utility class.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(common-kafka): producer adds traceparent header from current span

### Task 4: common-kafka — consumer traceparent extract + Observation wrap

**Files:**
- Modify: utils/common-kafka/.../consumer/BaseKafkaConsumer.java — handle() method wraps payload processing in `Observation.createNotStarted("kafka.consume", observationRegistry).observe(() -> handler.accept(payload, headers))`. Inside, extract from headers: if `traceparent` present, parse + set as parent context; else create new root observation.
- Test: BaseKafkaConsumerTest (mock ObservationRegistry): inbound record with traceparent header → handler called inside child observation with parent context set; without header → root observation.

- [ ] **Step 1: failing test.**
- [ ] **Step 2: implement.** Use `propagation.injector()` / `extractor()` from `io.micrometer.tracing.propagation.Propagator`. W3C propagator is default.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(common-kafka): consumer wraps handle() in Observation with traceparent parent

### Task 5: common-spring — actuator + tracing starter default

**Files:**
- Modify: utils/common-spring/pom.xml — verify spring-boot-starter-actuator + io.micrometer:micrometer-tracing-bridge-otel are present (transitively or explicit). Add explicit if missing.
- Modify: utils/common-spring/src/main/resources/META-INF/spring.factories OR common-spring autoconfig class — add @AutoConfiguration that sets `management.tracing.sampling.probability: 1.0` default (services override via env).
- Modify: utils/common-spring/.../config/TracingConfig.java (NEW or amend) — service-name resolution from `spring.application.name` (default), deployment-environment from `SPRING_PROFILES_ACTIVE` (default: dev).
- Test: TracingAutoConfigTest (ApplicationContextRunner): bean present; default probability 1.0; override via env works.

- [ ] **Step 1: failing test.**
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(common-spring): tracing auto-config — actuator + OTel bridge + sampling default

### Task 6: per-service yml additions (verification only)

**Files:**
- Verify: each service's application.yml has `spring.application.name` set (most already do; verify in payment/shipping/rating/search/media by `grep`).
- Verify: each service's compose stanza has `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` env (default 1.0; override to 0.1 in any production profile if shipped).
- No new code in any service. Only yml additions where missing.

- [ ] **Step 1:** grep -l 'spring.application.name' each */application.yml. Document gaps.
- [ ] **Step 2:** apply minimal yml patches for gaps.
- [ ] **Step 3:** ./mvnw -T1C install -DskipTests -q → exit 0.
- [ ] **Step 4: commit** chore(otel): per-service spring.application.name verification + sampling env

### Task 7: cross-service IT verification

**Files:**
- Create: e2e-tests/.../TracingPropagationIT.java (NEW — using existing common-test or fleet IT pattern; Testcontainers PG + Kafka + 2 service instances).

- [ ] **Step 1: implement IT** that:
  - Boots order-service + product-service in testcontainers.
  - Sends HTTP to order with `traceparent: 00-{traceId}-{spanId}-01`.
  - Triggers an order-lifecycle event.
  - Asserts: product-service consumer's ObservationRegistry has a span with parentId matching the inbound traceId.
  - Asserts: order's outbound HTTP to product carries the same traceparent.
- [ ] **Step 2: run** ./mvnw -pl e2e-tests test → GREEN (or record as separate epic if e2e-tests module doesn't exist).
- [ ] **Step 3: commit** test(otel): cross-service trace propagation IT

### Task 8: final whole-branch review

- [ ] Dispatch reviewer subagent: whole-branch diff vs main; spec D1-D4 + §5 audit; all 4 propagation paths tested; zero service-code regressions; log pattern includes traceId + spanId; sampling default 1.0; W3C format strict (version 00, 32-hex traceId, 16-hex spanId, 2-hex flags); producer interceptor + consumer wrap correctly thread traceparent; rest-client forwarder fires only when current span present. Fix rounds until APPROVED.