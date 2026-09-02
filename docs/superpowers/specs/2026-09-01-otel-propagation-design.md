# OpenTelemetry Propagation — Design (cross-cutting infra)

- Date: 2026-09-01
- Status: Draft (pending user ratification of 4 scope decisions)
- Scope: W3C `traceparent` + `tracestate` propagation through every service's outbound HTTP client AND every Kafka producer/consumer header. NO agent, NO collector, NO OTLP exporter in this epic — instrumentation hooks only. Operator wires the backend (Jaeger / Tempo / OTLP collector) later via Spring Boot's standard management.tracing endpoint.

## Verified ground truths

- common-logging already populates `MDC` with `MdcKey.CORRELATION_ID` from `X-Correlation-Id` header (CorrelationIdFilter.java:29, confirmed by confirm-hardening spec D9 §9). Forwarding X-Correlation-Id outbound is one `requestInitializer` line in `RestClientConfig` (order's existing pattern).
- Spring Boot 4 includes spring-boot-starter-actuator + io.micrometer:micrometer-tracing-bridge-otel by default when `management.tracing.sampling.probability` is set (fleet already pulls actuator via common-spring). No new pom dependency needed in core code, but verify each service.
- common-kafka BaseKafkaConsumer / BaseKafkaProducer currently do NOT propagate any headers outbound. Inbound headers are exposed via `ConsumerRecord.headers()` — consumer subclasses can read but not write. This epic adds outbound propagation in the producer path and reading in the consumer path.
- No service currently instruments its own @KafkaListener with Micrometer Observation API. This epic introduces the pattern via common-kafka, all services inherit.

## §1 Binding decisions

### D1 — Micrometer Tracing bridge (NOT OTel SDK directly) (decision Q1)

Use `spring-boot-starter-actuator` + `io.micrometer:micrometer-tracing-bridge-otel` (already pulled transitively by fleet BOM). Spring Boot auto-configures the OTel SDK behind Micrometer's Observation API. Service code uses `Observation` / `ObservedAspect` (or `@Observed` annotation) — NOT the raw OTel `Tracer`. Rationale:

- Fleet uses Micrometer for metrics (D9/D10 of every spec already has Micrometer counters). Tracing in the same model = one mental model.
- @Observed annotation is AOP-based; doesn't pollute service code (rating T3 nit #1 style — keep instrumentation non-invasive).
- Operators get OTel-format spans automatically (Micrometer Tracing OTel bridge).

### D2 — Scope: HTTP outbound + Kafka producer + Kafka consumer + inbound HTTP (decision Q2)

FOUR propagation paths, all required:

1. **Inbound HTTP** → servlet filter extracts `traceparent` (or generates one), stores in MDC, passes to controller.
2. **Outbound HTTP** (every RestClient / RestTemplate in the fleet) → RestClientBuilder requestInitializer adds `traceparent` from current Observation context.
3. **Kafka producer** (every record sent via KafkaTemplate or relay) → adds `traceparent` header from current Observation context.
4. **Kafka consumer** (every @KafkaListener) → extracts `traceparent` from inbound headers, wraps listener execution in child Observation.

V1 excludes: outbound DB query instrumentation (micrometer-jdbc exists but adds noise), gRPC (no gRPC in fleet), manual OTel Span creation in services (only via Observation API or @Observed).

### D3 — W3C traceparent + tracestate (decision Q3)

Header names: `traceparent` (W3C Trace Context, version 00) and `tracestate` (vendor-specific). Spring Boot auto-generates `traceparent` format `00-{traceId}-{spanId}-{flags}`. `tracestate` is empty in V1 (no vendor state).

Sampling: `management.tracing.sampling.probability: 1.0` in dev/compose (always-on), `0.1` in prod (10% — recommended starter, ops can override via env). Per-request sampling decisions propagate via traceparent flags.

### D4 — Correlation-Id stays; traceparent is orthogonal (decision Q4)

DO NOT replace X-Correlation-Id with traceparent. They serve different purposes:

- X-Correlation-Id = business-level correlation (e.g., user clicked from email campaign; support agent ticket). Persists across async boundaries that may not be in a single trace. Survives restart.
- traceparent = technical trace context for one request tree. Generated per-request, ephemeral.

Both are populated. common-logging MDC gains `traceId` + `spanId` IN ADDITION TO `correlationId`. Logs now include all three: `[traceId=abc spanId=def correlationId=ghi] user-service: ...`.

## §2 Touch points (concrete files)

**utils/common-logging** (NEW module responsibility or amend existing):
- CorrelationIdFilter: add traceparent extraction/generation alongside X-Correlation-Id; populate MDC `MdcKey.TRACE_ID` + `MdcKey.SPAN_ID`.
- Logging pattern: logback config include traceId + spanId in MDC pattern.
- RestClientConfig: add requestInitializer `RestClient.RequestHeadersInitializer` that copies traceparent from current Observation context.

**utils/common-kafka** (NEW additions):
- BaseKafkaProducer (if exists) or producer interceptor: add `traceparent` + `tracestate` headers to every outbound record from current Observation context.
- BaseKafkaConsumer.handle(): wrap payload processing in `Observation.createNotStarted("kafka.consume", observationRegistry).observe(...)`. Inside, set the extracted traceparent as parent context.

**utils/common-spring**:
- Verify spring-boot-starter-actuator + micrometer-tracing-bridge-otel are on the classpath (likely already, confirm).
- management.tracing.sampling.probability config added to common-spring auto-config default (1.0; override via service yml).

## §3 API surface

No new endpoints. Pure infra plumbing. /actuator/tracing (or /actuator/info if tracing metadata not separately exposed) shows current sampling config and bridge status.

## §4 Testing strategy

- Unit (common-logging): CorrelationIdFilter extracts/generates traceparent, populates MDC.
- Unit (common-kafka): BaseKafkaConsumer handle() invokes handler inside Observation with extracted parent; producer interceptor adds traceparent header.
- Integration (cross-service IT): invoke order → product via HTTP, verify traceparent is forwarded and span chain shows order→product. Invoke order publishes Kafka event, consumer trace has parent span = producer span.

## §5 Fleet impact (lane rules)

- **common-logging + common-kafka + common-spring = W1 lane** (single owner, single PR, all consumers pick up the change).
- **per-service changes: MINIMAL** — only yml config additions (management.tracing.sampling.probability if overriding default; service-name env if not already set).
- **out-of-scope touch points**: service code does NOT need to change to benefit. Forwarding is auto.
- Init SQL: no change.

## §6 Non-goals (binding)

OTLP exporter (Jaeger / Tempo / Datadog backend wiring — operator-side); span enrichment via @Observed on every service method (encourage but not enforced); span error recording (auto via observation API); DB query spans; baggage propagation (W3C baggage header); resource attributes auto-population (Spring Boot defaults to service.name, deployment.environment).

## §7 Open items

- Trace ID length: W3C 16-byte (32 hex chars). Spring Boot default. Confirmed by spec.
- Baggage: future. Allows propagating custom keys (tenant-id, etc.) via tracestate or baggage header.
- PII in span attributes: NEVER log email/userId in span name/attributes. PII redaction is a service-level concern.
- Sampling overrides per service: default common-spring applies 1.0; ops can override per service via env.
- Sampling decision propagation: yes (via traceparent flags, traceparent 00-{traceId}-{spanId}-01 means sampled; -00 means not sampled). Spring Boot handles.

## §8 Changelog

- 2026-09-01 (rev 0): Initial draft pending user ratification of D1 (Micrometer bridge), D2 (4 propagation paths), D3 (W3C traceparent only), D4 (correlation-id preserved).