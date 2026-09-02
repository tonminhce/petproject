# Production Readiness — Ops Runbook

- Date: 2026-09-01
- Status: Living ops document (epic: 2026-09-01-production-readiness, spec §4)
- Scope: operating the production compose overlay (`docker-compose.prod.yml`
  applied on top of `docker-compose.yml` with `.env.prod`). Dev behavior
  (base compose, direct service access, Postman E2E) is intentionally
  unchanged.

## 1. Files & invocation

| File | Role |
|------|------|
| `docker-compose.yml` | Dev/base topology. UNCHANGED by the prod epic. |
| `docker-compose.prod.yml` | Prod overlay: ingress-only ports, allowlist env, audit volume, rotated secrets. |
| `.env.prod.example` | Template for `.env.prod` (gitignored). |

Deploy:

```bash
cp .env.prod.example .env.prod   # fill real values — see §4.1–§4.5
docker compose --env-file .env.prod \
  -f docker-compose.yml -f docker-compose.prod.yml up -d
```

Both files must pass `docker compose config -q` (spec §4(3)). The overlay
uses `ports: !override []` (compose v2.24+) to REPLACE base port lists —
a plain overlay merge would append, not remove.

## §4.1 Deployment topology (BINDING)

### Ingress-only exposure

After the overlay, the ONLY published host port is the gateway ingress
(`${GATEWAY_INGRESS_PORT:-8080} → 8080`). Keycloak's admin port (`9090`), all
infrastructure ports (postgres/redis/kafka/elasticsearch/rustfs) and every
backend service port are stripped; containers remain reachable only on the
internal `ecommerce-network` by container DNS. The internal network is
untouched: service-to-service calls (rating→order, search→product,
order→tax/promotion/payment/shipping) traverse it directly and NEVER the
gateway edge (spec D1 ingress-only rule).

| Host port | Base (dev) | Prod overlay |
|-----------|------------|--------------|
| 8080 (gateway ingress) | published | published via `${GATEWAY_INGRESS_PORT}` — only ingress |
| 9090 (keycloak admin) | published | REMOVED — manage via admin API in-network |
| 5432 / 6379 / 9092 / 9200 / 9000-9001 (infra) | published | REMOVED |
| 8081–8094 (services) | published | REMOVED |

`mock-payment-provider` has no published port; disable it for real prod runs
with `--scale mock-payment-provider=0`.

### XFF trust — trusted reverse proxy is REQUIRED (T5 finding, Important)

The gateway's client-IP resolution (backoffice IP allowlist D5 AND the Redis
per-route rate-limit key) trusts the **first `X-Forwarded-For` entry**. Under
direct internet exposure XFF is entirely attacker-controlled: a forged header
both defeats the allowlist and forges rate-limit buckets. Therefore:

> **The gateway port MUST NOT be exposed directly to the internet.** Deploy
> behind a trusted reverse proxy / LB that **OVERWRITES** (not appends)
> `X-Forwarded-For` with the real client address before forwarding to
> `GATEWAY_INGRESS_PORT`.

The overlay sets `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS=1` (SCG
`XForwardedRemoteAddressResolver.maxTrustedIndex(1)`) to match the one-hop
topology above — only change it if the proxy count changes. (Known open item
from the T5 review: gating XFF trust behind an explicit trusted-proxy config
for the allowlist filter is future hardening; the role gates
(edge ADMIN + service `@PreAuthorize`) remain the real authorization
controls — the allowlist is defense-in-depth.)

### ADMIN_IP_ALLOWLIST wiring (D5)

- Semantics: env **ABSENT or EMPTY STRING = filter INACTIVE (allow-all)**;
  PRESENT + non-empty = deny non-matching sources with a 403 fleet envelope.
- ⚠ **`ADMIN_IP_ALLOWLIST` present-but-empty is a MISCONFIGURATION, not a
  safe default.** The overlay wires `ADMIN_IP_ALLOWLIST: ${ADMIN_IP_ALLOWLIST:-}`,
  so an unset `.env.prod` var silently yields the empty string = INACTIVE.
  **Deployment gate: the value must be a non-empty CIDR list in `.env.prod`.**
- Invalid CIDR entries fail gateway startup (fail-fast — the control never
  silently degrades).
- Always-bypassed paths: `/api/v1/webhooks/**` (payment + shipping webhooks)
  and `/actuator/health`.

### Secret rotation (D2) — 3 service clients + keycloak admin

Dev secrets (`changeme`) ship in the realm import file and base compose —
bootstrap truth only. Prod rotation is one-time per rotation cycle:

1. For `order-service`, `rating-service`, `search-service`: generate strong
   secrets; PUT each via the Keycloak admin API
   (`PUT /admin/realms/ecommerce/clients/{id}` → `secret`), service accounts
   are client_credentials.
2. Set the rotated values in `.env.prod`:
   `ORDER_SERVICE_CLIENT_SECRET`, `RATING_SERVICE_CLIENT_SECRET`,
   `SEARCH_SERVICE_CLIENT_SECRET` — the overlay injects them over the dev
   defaults. An EMPTY value FAILS CLOSED (Keycloak rejects the
   client_credentials grant); it never silently reuses `changeme`.
3. Set a strong `KEYCLOAK_ADMIN_PASSWORD` (and `KEYCLOAK_ADMIN`) in
   `.env.prod` before first prod start; rotate the admin console password on
   the same cycle.
4. Rolling restart: `docker compose ... up -d` re-creates only changed
   services.

### OTEL_EXPORTER_OTLP_ENDPOINT wiring (D3)

Env-gated: the OTLP span exporter is created ONLY when
`OTEL_EXPORTER_OTLP_ENDPOINT` is set (relaxed binding →
`otel.exporter.otlp.endpoint`). Absent → no exporter; tracing still populates
MDC (`traceId`/`spanId` in logs) and W3C `traceparent` propagation keeps
working end-to-end (HTTP + Kafka headers).

- The overlay carries the entry as a COMMENTED EXAMPLE on `gateway-service`;
  to export a service's spans, copy the same line into that service's block
  (every fleet service ships the common-spring tracing auto-config).
- Do NOT wire an empty value — `@ConditionalOnProperty` treats empty-string
  as present and would create the exporter with a blank endpoint.

### AUDIT_LOG_PATH volume (D6)

`@Audited` events are emitted per-service (never at the gateway). The overlay
sets `AUDIT_LOG_PATH=/var/log/audit/audit.jsonl` on the 10 audited services
(product, rating, tax, notification, shipping, payment, search, promotion,
media, inventory) and mounts the named volume `audit_logs` at
`/var/log/audit` (parent dirs are created by the sink; appends are crash-safe,
transient IO failures recover on the next event). If the var were unset,
events fall back to the stdout `AUDIT` logger. Ship `audit.jsonl` off-box
with filebeat/vector — the file sink is the seam (spec §6 open item: audit
sink shippers).

## 2. Known open items carried (spec §6)

- `/actuator/prometheus` is edge-reachable; protect it at the LB (path deny)
  until the overlay gains endpoint protection.
- Redis-backed distributed rate-limit buckets when the gateway scales beyond
  one instance (also closes the in-process bucket-map growth note).
- XFF trust gating for the allowlist filter (mirroring
  `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS`) — see §4.1.
- **Media purge FAIL-SAFE policy (F-3, H-4):** the production
  `MediaReferenceChecker` asks product-service's internal reference-count
  endpoint (`/internal/products/media-references/{id}`, SERVICE-token gated)
  how many live products still reference the media. When the check cannot
  PROVE zero references — product outage, non-2xx, timeout, malformed body,
  rejected service token — the answer is REFERENCED: the purge job skips the
  candidate with a WARN and retries next cycle. Purge never hard-deletes on
  doubt; soft-deleted objects accumulating during an outage is the accepted
  cost, bounded by delete volume. A successful count of 0 is the only
  purge-green answer. Media's outbox relay replays FAILED rows on later
  cycles (broker outage delays delivery, never kills it), and rows older than
  the 7d retention window age to the terminal DEAD state (metric + WARN).
  Media and inventory both write `@Audited` events to the `AUDIT_LOG_PATH`
  volume wiring in §4.1 (fleet-hardening H-7).

## Future hardening (ratified follow-ups, non-blocking)

1. **Actor resolution Keycloak-shape coupling (runbook item — STAYS):** audit
   actor AND the confirm() idempotency actor both resolve through
   `AuditActorResolver`'s azp-present-without-session-marker ⇒ service rule
   (degradation path is sub-first). Re-verify on Keycloak upgrades
   (KeycloakRealmImportIT gates the token shape). Both consumers were aligned
   in fleet-hardening H-6 so there is exactly ONE coupling point.
2. **Kafka wire-shape contract (BINDING, restated post-R1):** producers
   publish JSON-as-String via `KafkaMessagePublisher` →
   `stringKafkaTemplate` (StringSerializer ×2) — SINGLE-ENCODED UTF-8 JSON
   on the wire (R1). Consumers use `StringDeserializer` + the
   `BaseKafkaConsumer` unwrap-once boundary and MUST tolerate single- AND
   legacy double-encoded values (H-1 defense-in-depth for in-flight/retained
   records from pre-R1 producers). IT helpers MUST publish through the same
   production path (`KafkaMessagePublisher`); one legacy double-encoded pin
   per service is kept as in-flight-tolerance proof. New typed consumers
   inherit the tolerant base by construction — do not reintroduce typed
   `JsonDeserializer` bindings.

Closed items (kept for traceability, resolved by fleet-hardening):

- ~~Central OTel wiring~~ — CLOSED (R1/T5): the common-spring tracing
  `BeanPostProcessor` auto-injects the traceparent interceptor into EVERY
  `RestClient.Builder` bean; the 4 per-service manual sites were removed.
- ~~`confirm()` semantic~~ — CLOSED (H-6/T6): the controller resolves the
  actor by token shape (ADMIN → sub, SERVICE → `service:<azp>`); idempotency
  rows store the string actor label, so machine callers are no longer
  misattributed to a service-account UUID.

## Elasticsearch security (H34)

The dev compose flips `xpack.security.enabled=true` and keeps
`xpack.security.http.ssl.enabled=false` — a deliberate dev trade-off:

- **Why HTTP for dev:** the fleet's reverse proxy / LB is the only place TLS
  belongs; cert provisioning inside `docker-compose.yml` would require
  shipping a CA + cert pair, which is out of scope for the dev topology.
  The search-service client talks to ES over plain HTTP with BASIC auth.
- **Production must flip to HTTPS.** The search-service code already speaks
  HTTPS if the URL is `https://...` (the official `co.elastic.clients`
  Java client negotiates TLS automatically). Operators add the cert in the
  prod overlay:
  ```yaml
  - xpack.security.http.ssl.enabled=true
  - xpack.security.http.ssl.certificate=...
  - xpack.security.http.ssl.key=...
  ```
  and change `ELASTICSEARCH_URL` to `https://elasticsearch:9200` in `.env.prod`.
  No search-service code change required.
- **Credential rotation.** `ES_ADMIN_USERNAME` / `ES_ADMIN_PASSWORD` (in
  `.env` and `.env.example`) is the application admin user; the same value
  bootstraps the built-in `elastic` superuser via `ELASTIC_PASSWORD`. In
  prod, create a dedicated service user with the minimum required
  privileges (read + write on `search-*` indices) and remove the
  `elastic` superuser from the rotation set.

## Battery (stability gate — BINDING)

The battery is the fleet's full-suite stability gate, run from the repo root
before and after every hardening pass:

```bash
./mvnw -T1C install -DskipTests -q          # whole-reactor compile gate
./mvnw -pl <touched services...> test       # full suites of touched modules
docker compose config -q                    # when compose/overlay was touched
```

**Scope (BINDING since fleet-hardening H-11):** the battery permanently
includes the seven shared modules — their suites must never be masked again.

```bash
./mvnw -pl utils/common-core,utils/common-spring,utils/common-kafka,\
utils/common-keycloak,utils/common-logging,utils/common-security,\
utils/common-storage test \
  && ./mvnw -T1C install -DskipTests -q
```

Contract notes (H-9): ITs execute under `mvn test` via the per-module
surefire `**/*IT.java` includes — `mvn test`, not `failsafe`, is the
battery's integration gate; `install -DskipTests` is the fleet compile gate,
not a quality gate. `common-spring` intentionally ships a no-datasource
boot proof (`CommonLibraryStarterTests`) that opts JPA auditing out via
`shop.jpa.auditing.enabled=false` — JPA services keep the default
`matchIfMissing=true` behavior and are covered by their own suites.
