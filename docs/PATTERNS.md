# Fleet Patterns — Authoritative Reference

> **Every Wave B/C/D agent AND every maintainer MUST read this before touching a
> service.** When you change a pattern, prove it with research; update the
> `Reasoning` and `Citation` rows. The compliance tests in
> `utils/common-patterns` enforce most of these rules at build time.

This document is the single source of truth for fleet conventions. Whenever the
"best practice" question comes up, **cite a doc**, not memory.

---

## R1. DTOs are Java records, never `@Builder` on a record

**Rule:**
- Layer-bound records (DTOs, projections, event payload types) are `record` types
  with bean-validation annotations on the components (`@NotBlank`, `@Size`, …).
- No Lombok `@Builder`, `@Getter`, `@Setter`, `@Data` on a record. The canonical
  constructor is the construction API. Tests construct partial responses with
  positional arguments; if a partial matter, use a static factory
  (e.g., `TokenResponse.bearer(token, ttl)`).

**Why:** Records already produce a canonical constructor, accessors, equals,
hashCode, and toString. Adding Lombok on top generates a second builder class
with no benefit and creates a foot-gun: Jackson + records uses
`@JsonCreator`-discovered canonical-ctor binding, not the Lombok-generated
builder — so the `@Builder` API actively mis-encourages callers to construct
records piecemeal in tests while the runtime expects full canonical-ctor
serialisation.

**Reasoning:** Lombok `@Builder` on a record works (it generates a builder that
calls the canonical constructor), but the Lombok-official docs do not
document it as a best practice; the Spring/Java community consensus in 2026 is
to prefer the canonical constructor or a small hand-written factory.

**Citation:**
- https://projectlombok.org/features/Builder — Lombok official `@Builder` page.
- JEP 395 (Java 16 records) — canonical constructor is the canonical
  construction API; no builder required.

**Failure mode caught:** `TokenResponse.builder()...build()` in domain code or
tests; `@Getter` on a record component (redundant).

---

## R2. Domain exceptions map to 4xx, never 500

**Rule:**
- `BusinessException` thrown at the service layer carries the canonical
  `ErrorCode` (see `utils/common-core/.../exception/ErrorCode.java`) and the
  global `ApiExceptionHandler` returns the matching `HttpStatus` + i18n message.
- `DataIntegrityViolationException` (DB constraint violations: unique, check,
  FK) ALWAYS maps to **409 CONFLICT** with `ErrorCode.CONFLICT` —
  NOT 500.
- The raw `exception.getMostSpecificCause().getMessage()` is NEVER echoed to
  the client (it carries the constraint name, table name, column name, SQL
  state — a free schema map to any caller).

**Why:** A client that retried an idempotency key with the same payload needs
the 409 + domain code to know it was a duplicate; the operator (and the logs)
still need the full cause. The two audiences are served by two channels: the
response body keeps its semantic status, the log line keeps the stack trace.

**Reasoning:** Originally the global handler leaked the cause message because
the agent reasoned "constraints are conflicts, so 409 + the message is
fine". That reasoning falls apart as soon as the message itself contains
privileged content. The fix MUST keep the 409 + use a generic i18n key
(`error.conflict`) for the response.

**Citation:**
- Spring @ExceptionHandler docs (HTTP status mapping).
- OWASP "Improper Error Handling" guidance — never echo internal exception
  text in user-facing responses.

**Failure mode caught:** Any `@ExceptionHandler(DataIntegrityViolationException)`
returning `HttpStatus.INTERNAL_SERVER_ERROR`; any handler that interpolates
`exception.getMostSpecificCause().getMessage()` into the response body.

---

## R3. Kafka producer keys are Kafka-native names — no `properties.` prefix

**Rule:**
- Properties passed to `DefaultKafkaProducerFactory` use the canonical Kafka
  client key names: `bootstrap.servers`, `acks`, `enable.idempotence`,
  `compression.type`, `batch.size`, `linger.ms`,
  `max.in.flight.requests.per.connection`, `key.serializer`, `value.serializer`.
- Do NOT prefix any of these with `properties.` — that is a Kafka client
  config *key*, not a path. The broker silently ignores it.

**Why:** A key like `properties.max.in.flight.requests.per.connection=1` looks
plausible but the Kafka producer never reads it, so the actual
`max.in.flight.requests.per.connection` stays at its broker default (5) and
breaks the exactly-once-per-attempt recipe pinned at the build site.

**Reasoning:** The pre-existing line in our `KafkaProperties.build*` method
had this key wrong for months. H41 (Wave A) preserved the original behaviour
verbatim — proving that even carefully-reviewed work inherits this foot-gun if
not tested with an assertion.

**Citation:**
- Apache Kafka producer configs:
  https://kafka.apache.org/documentation/#producerconfigs
- Spring Boot Kafka `application-properties` reference (show which keys are
  first-class vs which go through `spring.kafka.producer.properties.*`):
  https://docs.spring.io/spring-boot/appendix/application-properties/

**Failure mode caught:** Any line in `KafkaProperties.buildProducerProperties()`
or `buildConsumerProperties()` whose key starts with `properties.`.

---

## R4. No `addTrustedPackages("*")` — deserialization trusts the wire contract, not classpath

**Rule:**
- Kafka consumer trusted packages are enumerated
  (`com.shop.common.avro`, the service-local package, etc.). Never
  `addTrustedPackages("*")` or `TrustedPackages.ALL`.
- Jackson polymorphic deserialization only when the discriminator field is
  controlled by the producer fleet.

**Why:** `addTrustedPackages("*")` is a deserialization gadget
amplifier — any class on the classpath reachable from a no-arg / setter entry
point becomes instantiable from a malicious payload (CVE-2017-7525 style
chains; newer Spring Security advisories keep the pattern warm).

**Citation:**
- Spring Kafka `JsonDeserializer.addTrustedPackages` javadoc (security
  warning).
- CVE-2017-7525 + Jackson polymorphic deserialization guidance.

**Failure mode caught:** Any `TrustedPackages.newTrustedPackages("*")`,
`addTrustedPackages("*")`, or equivalent wildcard.

---

## R5. Containers run non-root — both Dockerfiles AND Jib config

**Rule:**
- Every service Dockerfile ends with `USER appuser` after `addgroup -S appuser
  && adduser -S appuser -G appuser` and `COPY --chown=appuser:appuser …`.
- The root `pom.xml` Jib plugin config sets
  `<container><user>100:101</user></container>` — same UID/GID as the Docker
  approach — so Jib-built images (the path the production `docker-compose.yml`
  uses) match the Dockerfile path.

**Why:** A container escape (JRE CVE / Spring app dependency / supply-chain
side-load) hands the attacker root in the host kernel namespace when the
container process runs as UID 0. Linux best practice since Docker's "rootless"
push is "drop privileges as early as possible".

**Citation:**
- eclipse-temurin Dockerfiles (Alpine/JRE) — no unprivileged user out of the
  box; convention `RUN addgroup -S appuser && adduser -S appuser …`.
- Jib `container.user` plugin documentation
  (https://github.com/GoogleContainerTools/jib).

**Failure mode caught:** A `Dockerfile` for a service with no `USER appuser`
directive; a Jib config without `<container><user>`.

---

## R6. Env vars referenced in `.yml` MUST exist in `.env.example`

**Rule:**
- Every `${XXX}` interpolation in `application.yml`, `application-dev.yml`,
  `docker-compose.yml`, and `docker-compose.prod.yml` resolves either to a
  Spring built-in placeholder or to a variable defined in `/.env.example`
  with a sensible default (or empty) and a `CHANGE IN PROD` comment for any
  secret/credential.
- Renaming an env var must update all five touchpoints in a single commit.

**Why:** Wave A moved `ELASTICSEARCH_USERNAME/PASSWORD` →
`ES_ADMIN_USERNAME/ES_ADMIN_PASSWORD` in `/.env` + the search-service Docker
compose entry + `docker-compose.prod.yml`, but the per-service
`application.yml` still referenced the old names. In local-dev (no compose),
the variables never reach the container → search-service falls back to
anonymous → ES (now xpack-secured) returns 401.

**Citation:**
- docker compose .env file interpolation rules:
  https://docs.docker.com/compose/environment-variables/env-file/
- Spring Boot Externalized Configuration:

**Failure mode caught:** Any `${X}` reference where `X` does not appear in
`/.env.example` or any Spring built-in placeholder list.

---

## R7. Page size cap + page response envelope

**Rule:**
- Every list endpoint returns `ApiResponse<PageResponse<T>>` (not
  `List<T>`).
- Default page size is 20; `maxPageSize` is hard-capped at 100 (or per-service
  rule from the review).
- Storefront side binds `?page&size`, backoffice side respects caps.

**Why:** A storefront `findAll` returning `List<Product>` triggers the second
Trino-size incident in any e-commerce fleet. Fleet rule 4.

**Citation:**
- Spring Data `Pageable` design pattern.

**Failure mode caught:** A controller returning `List<T>` instead of
`PageResponse<T>` or skipping the page-size guard.

---

## R8. Auth rules: storefront read, backoffice ADMIN write

**Rule:**
- `Storefront*Controller` has no `@PreAuthorize` (public read).
- `Backoffice*Controller` has @PreAuthorize for ADMIN or hasRole('MANAGER')
  per the existing service pattern. No method-level `hasAuthority('ADMIN')`
  diverging from the role spelling.

**Why:** Fleet pattern rule 5.

**Failure mode caught:** `StorefrontProductController.create` missing the
backoffice redirect; a `hasAuthority('ADMIN')` mixed with `hasRole('ADMIN')`.

---

## R9. ddl-auto=validate, Liquibase-only

**Rule:**
- `spring.jpa.hibernate.ddl-auto=validate` on production profile.
- Schema changes ONLY via Liquibase changelog in `db/changelog/*.yaml`.
- Tests override to `ddl-auto=none` (Testcontainers).

**Why:** Fleet pattern rule 8.

**Failure mode caught:** A new entity column without a corresponding
Liquibase changeset.

---

## R10. `AuthenticatedUser.requireCurrent()` — never `jwt.getSubject()` for users

**Rule:** Authenticated services read the current user via
`AuthenticatedUser.requireCurrent()` (helper in `common-security`). The JWT
`subject` is the user's UUID; the username lives in a custom claim or in the
DB look-up — calling `userService.findByUsername(jwt.getSubject())` returns
404 because the subject is not the username.

**Failure mode caught:** `userService.findByUsername(jwt.getSubject())`,
`hasAuthority('ADMIN')` next to a `hasRole('USER')`.

---

## R11. ES xpack security posture

**Rule:**
- `xpack.security.enabled=true` even in dev — the cluster is never anonymous.
- Dev keeps `xpack.security.http.ssl.enabled=false` (HTTP + BASIC) with a
  `CHANGE IN PROD` marker in `.env.example`; production overlay enables HTTPS
  with a real cert.
- All `search-service` ES clients use the same credential env vars
  (`ES_ADMIN_USERNAME`, `ES_ADMIN_PASSWORD`) — not the older
  `ELASTICSEARCH_USERNAME/PASSWORD` names.

**Citation:** Elastic Stack security hardening checklist.

**Failure mode caught:** `xpack.security.enabled=false`; ES env-var names
mismatched between compose and per-service yml.

---

## R12. Outbox + claim lock

**Rule:**
- Every `OutboxEventRepository.claimOnePending(...)` uses `PESSIMISTIC_WRITE
  + SKIP LOCKED` and runs the publish inside the claim transaction.
- A scheduler reclaims rows still in `SENDING` past a heartbeat horizon
  (crash-mid-send recovery — without it the row strands in `SENDING`, same
  loss class as the original notification C12).

**Failure mode caught:** An `OutboxEventRelay` selecting pending rows
without `SKIP LOCKED`, or processing them outside the claim tx.

---

## How to apply this

1. **Before patching** for any wave finding, open this doc, find the matching
   rule, click through the citation, then design the fix.
2. **After patching** run the harness:
   ```bash
   ./mvnw -T1C -pl utils/common-patterns test
   ```
   The build fails if any rule is regressed.
3. **In your commits** cite the rule (e.g., `R2 (H32)`) in the commit body.
