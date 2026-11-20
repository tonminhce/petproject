# Fleet Patterns: Authoritative Reference

> **Every Wave B/C/D agent AND every maintainer MUST read this before touching a
> service.** When you change a pattern, prove it with research; update the
> `Reasoning` and `Citation` rows. The quality gate in
> `config/checkstyle.xml` and automated tests enforce these rules at build time.

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
builder: so the `@Builder` API actively mis-encourages callers to construct
records piecemeal in tests while the runtime expects full canonical-ctor
serialisation.

**Reasoning:** Lombok `@Builder` on a record works (it generates a builder that
calls the canonical constructor), but the Lombok-official docs do not
document it as a best practice; the Spring/Java community consensus in 2026 is
to prefer the canonical constructor or a small hand-written factory.

**Citation:**
- https://projectlombok.org/features/Builder: Lombok official `@Builder` page.
- JEP 395 (Java 16 records): canonical constructor is the canonical
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
  FK) ALWAYS maps to **409 CONFLICT** with `ErrorCode.CONFLICT`: 
  NOT 500.
- The raw `exception.getMostSpecificCause().getMessage()` is NEVER echoed to
  the client (it carries the constraint name, table name, column name, SQL
  state: a free schema map to any caller).

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
- OWASP "Improper Error Handling" guidance: never echo internal exception
  text in user-facing responses.

**Failure mode caught:** Any `@ExceptionHandler(DataIntegrityViolationException)`
returning `HttpStatus.INTERNAL_SERVER_ERROR`; any handler that interpolates
`exception.getMostSpecificCause().getMessage()` into the response body.

---

## R3. Kafka producer keys are Kafka-native names: no `properties.` prefix

**Rule:**
- Properties passed to `DefaultKafkaProducerFactory` use the canonical Kafka
  client key names: `bootstrap.servers`, `acks`, `enable.idempotence`,
  `compression.type`, `batch.size`, `linger.ms`,
  `max.in.flight.requests.per.connection`, `key.serializer`, `value.serializer`.
- Do NOT prefix any of these with `properties.`: that is a Kafka client
  config *key*, not a path. The broker silently ignores it.

**Why:** A key like `properties.max.in.flight.requests.per.connection=1` looks
plausible but the Kafka producer never reads it, so the actual
`max.in.flight.requests.per.connection` stays at its broker default (5) and
breaks the exactly-once-per-attempt recipe pinned at the build site.

**Reasoning:** The pre-existing line in our `KafkaProperties.build*` method
had this key wrong for months. H41 (Wave A) preserved the original behaviour
verbatim: proving that even carefully-reviewed work inherits this foot-gun if
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

## R4. No `addTrustedPackages("*")`: deserialization trusts the wire contract, not classpath

**Rule:**
- Kafka consumer trusted packages are enumerated
  (`com.shop.common.avro`, the service-local package, etc.). Never
  `addTrustedPackages("*")` or `TrustedPackages.ALL`.
- Jackson polymorphic deserialization only when the discriminator field is
  controlled by the producer fleet.

**Why:** `addTrustedPackages("*")` is a deserialization gadget
amplifier: any class on the classpath reachable from a no-arg / setter entry
point becomes instantiable from a malicious payload (CVE-2017-7525 style
chains; newer Spring Security advisories keep the pattern warm).

**Citation:**
- Spring Kafka `JsonDeserializer.addTrustedPackages` javadoc (security
  warning).
- CVE-2017-7525 + Jackson polymorphic deserialization guidance.

**Failure mode caught:** Any `TrustedPackages.newTrustedPackages("*")`,
`addTrustedPackages("*")`, or equivalent wildcard.

---

## R5. Containers run non-root: both Dockerfiles AND Jib config

**Rule:**
- Every service Dockerfile ends with `USER appuser` after `addgroup -S appuser
  && adduser -S appuser -G appuser` and `COPY --chown=appuser:appuser …`.
- The root `pom.xml` Jib plugin config sets
  `<container><user>100:101</user></container>`: same UID/GID as the Docker
  approach: so Jib-built images (the path the production `docker-compose.yml`
  uses) match the Dockerfile path.

**Why:** A container escape (JRE CVE / Spring app dependency / supply-chain
side-load) hands the attacker root in the host kernel namespace when the
container process runs as UID 0. Linux best practice since Docker's "rootless"
push is "drop privileges as early as possible".

**Citation:**
- eclipse-temurin Dockerfiles (Alpine/JRE): no unprivileged user out of the
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
- Default page size is 10 (`PageableConstant.DEFAULT_PAGE_SIZE = 10`); `maxPageSize` is hard-capped at 100-200 (`PageableConstant.MAX_PAGE_SIZE = 200`).
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

## R10. `AuthenticatedUser.requireCurrent()`: never `jwt.getSubject()` for users

**Rule:** Authenticated services read the current user via
`AuthenticatedUser.requireCurrent()` (helper in `common-security`). The JWT
`subject` is the user's UUID; the username lives in a custom claim or in the
DB look-up: calling `userService.findByUsername(jwt.getSubject())` returns
404 because the subject is not the username.

**Failure mode caught:** `userService.findByUsername(jwt.getSubject())`,
`hasAuthority('ADMIN')` next to a `hasRole('USER')`.

---

## R11. ES xpack security posture

**Rule:**
- `xpack.security.enabled=true` even in dev: the cluster is never anonymous.
- Dev keeps `xpack.security.http.ssl.enabled=false` (HTTP + BASIC) with a
  `CHANGE IN PROD` marker in `.env.example`; production overlay enables HTTPS
  with a real cert.
- All `search-service` ES clients use the same credential env vars
  (`ES_ADMIN_USERNAME`, `ES_ADMIN_PASSWORD`): not the older
  `ELASTICSEARCH_USERNAME/PASSWORD` names.

**Citation:** Elastic Stack security hardening checklist.

**Failure mode caught:** `xpack.security.enabled=false`; ES env-var names
mismatched between compose and per-service yml.

---

## R12. Outbox + claim lock

**Rule:**
- Every outbox relay claims per aggregate, never per table head: the claim
  takes a transaction scoped advisory lock on the aggregate id and refuses an
  aggregate that still has an event in `SENDING`. The events of one aggregate
  therefore reach the broker in ascending outbox id order, also when several
  relay instances publish concurrently.
- A claim stamps the lease `status = SENDING`, `claimed_at = now()` and
  `lease_owner = <instance>`.
- A scheduler reclaims rows still in `SENDING` past a heartbeat horizon
  (crash-mid-send recovery: without it the row strands in `SENDING`, same
  loss class as the original notification C12). The reclaim also re-arms rows in
  `SENDING` whose `claimed_at` is NULL.
- A graceful shutdown hands back only the leases that were never published;
  a lease whose delivery was confirmed is finalised as `SENT`.

The full contract, the failure modes and the regression tests are documented in
[OUTBOX-RELAY-CONTRACT.md](OUTBOX-RELAY-CONTRACT.md).

**Failure mode caught:** An `OutboxEventRelay` selecting pending rows
without `SKIP LOCKED`, or processing them outside the claim tx; a claim that
lets two instances publish the events of one aggregate concurrently; a recovery
scan that leaves a `SENDING` row without a lease timestamp stranded forever;
a shutdown that hands back a lease whose event is already on the broker.

---

## How to run the gate
 
1. **Before patching** for any wave finding, open this doc, find the matching
   rule, click through the citation, then design the fix.
2. **Validate rules locally** with Checkstyle before committing:
   ```bash
   ./mvnw -T1C -pl <changed-module> validate
   # Or run across the entire fleet:
   ./mvnw -T1C validate
   ```
   The build fails immediately if any fleet rule (R1, R3, R4) or code hygiene rule is violated.
3. **Verify tests & quality checks**:
   ```bash
   ./mvnw -T1C -pl utils/common-spring test
   ./mvnw -T1C verify
   ```
4. **Run SonarQube quality gate**:
   ```bash
   docker compose -f docker-compose.sonar.yml up -d
   ./scripts/setup-sonar-project.sh
   ./scripts/run-sonar-scan.sh
   ```
5. **In your commits** cite the rule (e.g., `R2 (H32)`) in the commit body.

---

## R13. A payment is captured only on a confirmed provider outcome

**Rule:**
- `ProviderResult` carries an explicit outcome: `CONFIRMED`, `REQUIRES_CONFIRMATION`,
  `REJECTED` or `INDETERMINATE`. A two-field boolean cannot express a payment that
  exists but still awaits client confirmation, and that ambiguity is how an order was
  confirmed as paid while no money had moved.
- `accepted()` is true only for `CONFIRMED`.
- A payment reaches `CAPTURED` only on a `CONFIRMED` result, and only that transition
  publishes the capture lifecycle event.
- A provider timeout is `INDETERMINATE`, never `REJECTED`. An indeterminate outcome
  raises a retryable error so the caller can retry, and it never marks the payment
  captured or failed.

**Why:** A Stripe intent created with confirmation disabled is a real intent that the
client must confirm with the client secret. Treating the creation as a capture both
loses the revenue and lies to every downstream fulfilment step.

**Citation:**
- Stripe PaymentIntent confirmation: https://docs.stripe.com/payments/payment-intents
- Stripe idempotent requests: https://docs.stripe.com/api/idempotent_requests

**Failure mode caught:** A capture path that assigns `CAPTURED` from the result of a
provider call that only created an intent.

---

## R14. An unknown payment provider is rejected, never substituted

**Rule:**
- The provider registry rejects an unknown or blank provider name with the domain
  error. It never falls back to another provider.
- A test-double provider is registered only when it is the configured provider, so it
  cannot silently back a production payment.
- The primary provider bean fails fast at startup when the configured provider is not
  registered, rather than falling back to an arbitrary provider.
- A refund addresses the provider that owns the original intent, using the stored
  provider intent identifier.

**Why:** A refund that executes against the wrong provider and is then marked
`REFUNDED` returns no money to the customer while the order says it did.

**Failure mode caught:** A factory that returns the default provider for an unknown
name, combined with a provider whose every call succeeds.

---

## R15. The rate-limit identity comes from the transport peer

**Rule:**
- The client address is the transport peer address. A forwarding header is consulted
  only through an explicit trusted proxy allowlist that is configuration driven.
- When the trusted proxy configuration is absent, no forwarding header is trusted.
- A value that enters a rate-limit key is validated as a literal address. A value that
  is not a literal address is discarded, and the peer address is used instead.
- No request-supplied value may trigger a name resolution.

**Why:** Indexing into a forwarding header by a hop count lets the client choose its
own rate-limit bucket and its own allowlist verdict. Resolving a non-literal entry
blocks the reactor event loop and produces a fresh budget per request.

**Citation:**
- Spring Cloud Gateway forwarded headers:
  https://docs.spring.io/spring-cloud-gateway/reference/webflux/globalfilters.html
- OWASP HTTP headers: https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html

**Failure mode caught:** A client address derived from an index into a client supplied
header, with no trusted proxy allowlist.

---

## R16. An authorization guard fails closed

**Rule:**
- A guard that cannot prove the caller raises. An empty security context is a
  rejection, not a pass.
- A caller that acts on behalf of another user presents an authorised-party identity
  that the platform has bound to that user. A request parameter alone never
  authorizes a cross-user read.
- An administrator may act for any user; that is an explicit role check, not a default.

**Why:** A guard that returns when the context is empty is an allow-by-default
primitive, and it was used on an endpoint that exposes another user's order data.

**Failure mode caught:** A guard whose happy path is "no context, no objection".

---

## R17. The platform user is resolved by the external identity

**Rule:**
- The identity provider identifier is the only key that resolves a platform user.
  The generated primary key is an internal surrogate and is never taken from a token
  claim.
- A repository lookup by the external identifier exists for every path that starts
  from an authenticated principal.

**Why:** Two identifier spaces used interchangeably made the self-service profile and
address surfaces unable to resolve a row, because the token subject and the generated
primary key are different values.

**Failure mode caught:** `UUID.fromString(token.getSubject())` passed into a lookup by
the generated primary key.

---

## R18. The Kafka consumer mapper follows the wire contract

**Rule:**
- The consumer object mapper is configured like the application mapper, so an additive
  change to an event contract does not break the consumers of that contract.
- Every listener factory has a dead-letter recoverer. A factory without a recoverer
  logs the record and skips it, which loses the event without any dead-letter record.
- The shared consumer configuration pins the commit semantics explicitly rather than
  inheriting a framework default.
- The dead-letter replay endpoint requires an administrative role and validates the
  requested topic against the topic contract. The replay keeps its committed offsets,
  so a record is published once in total.

**Why:** The default recoverer logs the failed record after the retries are exhausted.
Without a recoverer the record is simply skipped. And a consumer that rejects unknown
properties turns one additive field into a poison record for every consumer of that
event.

**Citation:**
- Spring Kafka error handling:
  https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- Spring Kafka topic contract enforcement is already enforced by `KafkaTopicContractTest`.

**Failure mode caught:** A bare `new ObjectMapper()` on the consumer path, and a
listener factory built without a recoverer.

---

## R19. A dependency failure is reported as a dependency failure

**Rule:**
- A call to a downstream dependency that cannot be reached, that times out, that
  returns a non-success status, or whose payload cannot be parsed, is reported as a
  dependency failure. The status is 503 and the code is the platform dependency
  failure code, never a business code.
- A business denial is produced only from a response the dependency actually
  returned. "The dependency said no" and "the dependency did not answer" are
  different outcomes and must not share a status code.
- A dependency that answers with a missing or malformed payload is a dependency
  failure, not a denial.
- The circuit breaker that guards the call observes the failure. The failure must
  escape the guarded call so the breaker can record it, and an open breaker is
  reported as a dependency failure as well.
- The dependency failure keeps the original cause so the transport fault remains
  diagnosable.

**Why:** An end-to-end run returned 403 for a rating submission while the upstream
purchase-verification call had failed with a socket read timeout. A failure to
determine was reported as "this user did not buy this product". The business rule
was correct, the dependency was unhealthy, and the two were indistinguishable in
the response, in the metrics, and in the on-call queue.

**Citation:**
- Spring RestClient error handling:
  https://docs.spring.io/spring-framework/reference/web/rest-clients.html#rest-clients.retrieve
- Resilience4j circuit breaker:
  https://resilience4j.readme.io/docs/getting-started-3

**Failure mode caught:** A `catch (RestClientException) { return false; }` on an
eligibility or authorisation check, with the circuit breaker placed inside the catch
so it never records the failure.

