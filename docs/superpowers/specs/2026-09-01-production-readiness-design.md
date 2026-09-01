# Production Readiness — Design

- Date: 2026-09-01
- Status: Approved (user-drafted plan reviewed; 6 findings adopted: F1 ingress-only,
  F2 file-placement, F3 audit-layer, F4 allowlist-default, F5 bucket4j scope, F6 error codes)
- Scope: cross-cutting. W1 = common-* + gateway + realm import; W2 = per-service
  enablement. NO service business-logic changes. media-service is a separate epic.

## Verified ground truths

- **9** `Backoffice*Controller` across 8 services (product, rating, tax×2, notification,
  shipping, payment, search, promotion) ↔ **9** `BACKOFFICE_*` ApiPaths constants
  (RATINGS, PRODUCTS, SEARCH, PROMOTIONS, TAX_CLASSES, TAX_RATES, NOTIFICATIONS,
  PAYMENTS, SHIPMENTS). Gateway `ServiceRoute` has NO backoffice entries today.
- utils/ modules: common-core, common-kafka, common-keycloak, common-logging,
  common-security, common-storage, common-spring. `CorrelationIdFilter` (X-Correlation-Id
  MDC) lives in common-spring/web/filter.
- Realm import: `docker/keycloak/import/ecommerce-realm.json` — contains only
  `ecommerce-client`. Runtime consumers of client_credentials today = exactly 3:
  order (payment/promotion/tax/shipping calls), rating (order), search (product).
- Gateway actuator exposes health,info,prometheus only. Webhook routes exist
  (payment/shipping). Bucket4j NOT in any pom (new dependency). Service-to-service
  calls traverse the docker network DIRECTLY, never the gateway edge (rating→order,
  search→product, order→4 providers).
- Fleet envelope: ApiResponse; ErrorCode convention `XXX-####` + http status;
  ERR-04xx family exists for shared/validation (exact rate-limit/IP codes pinned at
  plan time).

## D1 — Backoffice edge routing (INGRESS ONLY — binding)

Gateway routes the 9 `BACKOFFICE_*` path prefixes. Edge filter requires JWT role
ADMIN (realm role claim, same claim services already check via hasRole). Ordering of
edge filters: **IP allowlist → rate limit → role gate → route** (no audit slot — see
D6). **BINDING SEMANTICS: the edge is INGRESS ONLY.** Internal service-to-service
calls NEVER traverse the gateway; SERVICE+ADMIN endpoints (verify-purchase,
backoffice product list for reindex) stay reachable ONLY on the internal network.
The prod compose overlay restricts INGRESS ports, NOT the internal network. Dev base
compose keeps direct-service access (Postman E2E flows unchanged).

## D2 — Per-service Keycloak clients

`ecommerce-realm.json` gains 3 confidential clients: `order-service`, `rating-service`,
`search-service` (client_credentials, service accounts enabled). Dev secrets ship in
the import file (matches current compose env values). Prod rotation = one-time
Keycloak admin API PUT (ops runbook §4 item) — compose env `*_CLIENT_SECRET` already
overrides at container start; the import file is dev/bootstrap truth only.

## D3 — OTel / W3C traceparent propagation

Micrometer Tracing with the OTel bridge (Boot-managed BOM), OTLP exporter env-gated
(`OTEL_EXPORTER_OTLP_ENDPOINT` absent → no exporter, tracing still populates MDC).
Propagation: W3C traceparent/tracestate on HTTP (outbound via shared RestClient
interceptor in common-spring; inbound extracted automatically) + Kafka records
(common-kafka producer injects `traceparent` header; consumer extracts and continues
the span). `X-Correlation-Id` (business) and `traceparent` (technical) COEXIST — MDC
carries both; CorrelationIdFilter untouched.

## D4 — Gateway rate limiting

Bucket4j (in-process token bucket, new gateway dependency). Buckets keyed
remote-IP: `/api/v1/backoffice/**` = 10 req/min; `/api/v1/search/**` = 60 req/min;
everything else unlimited. Rejection = 429 + fleet ApiResponse envelope, code per
F6 convention. Open item (§6): Redis-backed distributed buckets when the gateway
scales beyond one instance.

## D5 — Backoffice IP allowlist

Gateway filter reading `ADMIN_IP_ALLOWLIST` (comma-separated CIDRs).
**Binding semantics: env ABSENT = filter INACTIVE (allow all); env PRESENT = deny
any non-matching source with 403 fleet envelope.** Prod overlay always sets it.
Bypass list (never IP-blocked): `/api/v1/webhooks/**`, `/actuator/health`.

## D6 — Audit logging (service layer, not gateway)

common-logging: `@Audited(action="…", resourceType="…")` annotation + aspect.
Applied to every mutating endpoint of the 9 backoffice controllers. Emits ONE
structured JSON line per invocation to `AUDIT_LOG_PATH` (default: stdout via the
existing logging pipeline). Payload (PII-safe, binding):
`{timestamp, actor (JWT sub UUID or clientId — never email/phone/name), action,
resourceType, resourceId (UUID — never titles), outcome, correlationId, traceId}`.
Aspect reads the security context + MDC; no new ErrorCode (domain codes unchanged;
audit failures NEVER block the request — log-and-continue).

## §4 Ops & contracts

1. Prod deploy runbook: set ADMIN_IP_ALLOWLIST; rotate 3 service-client secrets via
   Keycloak admin API; set OTEL endpoint; mount AUDIT_LOG_PATH volume.
2. Filter contracts: D5/D4 return fleet ApiResponse envelopes; D1 role gate accepts
   ONLY realm-role ADMIN at the edge.
3. Both compose files (`docker-compose.yml` + prod overlay) must pass `config -q`.
4. Rate-limit headers (X-RateLimit-Remaining) on 429 for debuggability.

## §5 Non-goals (binding)

TLS termination (LB/ingress), DDoS/WAF (edge provider), Vault/secrets manager,
admin UI (frontend epic), multi-tenant, service mesh, per-user service-level rate
limits, distributed rate-limit store.

## §6 Open items

Redis-backed rate buckets on gateway scale-out; ClamAV media scanning (media epic);
CDN in front of presigned URLs; prometheus endpoint protection in prod overlay;
audit sink shippers (filebeat/vector) — file output is the seam.
