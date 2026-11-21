# Dependency Graph — petproject @ f242158265163d65ef73d378331d2bd7a92479ee

Independent audit artifact, derived from `pom.xml`, `utils/pom.xml`, per-module poms, and the
packaged gateway jar (stale build evidence, labeled as such).

## 1. Reactor layout

- Root `pom.xml` = `com.shop.microservices:shop-microservices:1.0-SNAPSHOT`, parent
  `spring-boot-starter-parent:4.1.1`, Java 25, Spring Cloud BOM `2025.1.0`
  (`pom.xml:6-14,34,43,79-84`).
- `<modules>`: utils + 14 services (`pom.xml:16-32`).
- `utils/pom.xml` `<modules>`: common-config, common-core, common-security, common-logging,
  common-keycloak, common-kafka, common-spring, common-storage (`utils/pom.xml:18-27`).
  **`common-patterns` is NOT a module** — see §4.
- Every common lib is version-managed to `1.0-SNAPSHOT` in the root
  `dependencyManagement` (`pom.xml:85-124`) → **all 14 services are hard SNAPSHOT-coupled to
  the libs; a lib change requires a full-repo rebuild** (accepted monorepo trade-off, but it
  means there is no independent lib release line despite the "single entry point" framing).

## 2. Shared-lib dependency matrix (compile scope unless noted)

Read from each module/service pom directly.

| Consumer | common-config | common-core | common-security | common-logging | common-keycloak | common-kafka | common-storage | common-spring |
|---|---|---|---|---|---|---|---|---|
| common-spring | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ (optional) | ✔ (optional) | — |
| common-security | — | ✔ | — | — | — | — | — | — |
| common-logging | — | ✔ | — | — | — | — | — | — |
| common-keycloak | — | ✔ | — | — | — | — | — | — |
| common-kafka | — | — | — | — | — | — | — | — |
| common-storage | — | — | — | — | — | — | — | — |
| common-core | — | — | — | — | — | — | — | — |
| gateway-service | ✔ | ✔ (JPA excluded, `gateway-service/pom.xml:28-37`) | — | — | — | — | — | — |
| auth-service | — | — | — | — | — | — | — | ✔ (+ test-jar) |
| product-service | — | (transitive) | — | ✔ | — | ✔ | — | ✔ (+ test-jar) |
| order-service | — | (transitive) | — | ✔ | — | ✔ | — | ✔ (+ test-jar) |
| payment-service | — | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ (+ test-jar) |
| inventory-service | — | (transitive) | — | ✔ | — | ✔ | — | ✔ (+ test-jar) |
| shipping-service | — | ✔ | — | ✔ | ✔ | ✔ | — | ✔ (+ test-jar) |
| notification-service | — | ✔ | ✔ | — | ✔ | ✔ | — | ✔ (+ test-jar) |
| rating-service | — | ✔ | ✔ | ✔ | — | ✔ | — | ✔ (+ test-jar) |
| search-service | — | (transitive) | — | ✔ | — | ✔ | — | ✔ (+ test-jar) |
| promotion-service | — | ✔ | ✔ | ✔ | ✔ | ✔ | — | ✔ (+ test-jar) |
| tax-service | — | ✔ | ✔ | ✔ | ✔ | — | — | ✔ (+ test-jar) |
| favourite-service | — | — | — | — | — | — | — | ✔ |
| media-service | — | (transitive) | — | — | — | ✔ | ✔ | ✔ (+ test-jar) |

Practical rule: **`common-spring` is the universal aggregator** — it transitively carries
common-core/-security/-logging/-keycloak plus web, validation, oauth2-resource-server,
actuator, prometheus, OpenTelemetry, springdoc, MapStruct, resilience4j-spring-boot4,
spring-dotenv, httpclient5 (`utils/common-spring/pom.xml`). The explicit per-service entries
above only add kafka/storage/logging/keycloak/infra where the service touches them directly.
common-config contains **no Java** — it is a single `application-shared.yml` imported via
`spring.config.import` (`gateway-service/.../application.yml:7-8` and every service yml).

Dependency graph (arrows = depends on):

```
                ┌──────────────▶ common-config (yml only)
common-spring ──┼──────────────▶ common-core ──▶ spring-data-jpa starter, jakarta.persistence
                ├──────────────▶ common-security ──▶ common-core
                ├──────────────▶ common-logging ──▶ common-core, spring-boot-starter-aop (4.0.0-M2 pin!)
                ├──────────────▶ common-keycloak ──▶ common-core, httpclient5, otel-api
                ├──────────────▶ common-kafka (optional) ──▶ spring-kafka, spring-retry (UNUSED),
                │                                             jackson2 + jackson3 (tools.jackson)
                └──────────────▶ common-storage (optional) ──▶ AWS SDK v2 S3
services ──▶ common-spring (+ selected direct libs above)
gateway  ──▶ common-config, common-core(-JPA), webflux, SCG webflux, oauth2-resource-server,
             data-redis, resilience4j-circuitbreaker(2.4.0)/bulkhead(UNVERSIONED)/micrometer(2.4.0)
```

## 3. Version / conflict analysis

| Item | Fact | Verdict |
|---|---|---|
| resilience4j fleet version | `${resilience4j.version}=2.4.0` (`pom.xml:48`) managed only for `resilience4j-spring-boot4`, `resilience4j-reactor`, `resilience4j-micrometer` (`pom.xml:157-171`) | gateway's direct `resilience4j-bulkhead` has **no version** (`gateway-service/pom.xml:56-59`) and no BOM in the chain manages it: `spring-cloud-dependencies-2025.1.0.pom` contains 0 `resilience4j` mentions; `spring-boot-dependencies-4.1.1.pom` contains 0. Last packaged jar (`target/gateway-service-1.0-SNAPSHOT.jar`, build 2025-11-24, predates current pom) contains **circuitbreaker 2.4.0 + micrometer 2.4.0 alongside core/bulkhead/retry/ratelimiter/timelimiter 2.3.0** — a split-version classpath | ARCH-2 (P1/P2) |
| spring-boot-starter-aop | pinned `4.0.0-M2` milestone in root `dependencyManagement` (`pom.xml:74`, `197-201`) overriding Boot 4.1.1 GA management; consumed by common-logging (aspects ship to every service); `spring-boot-starter-aop/4.0.0-M2` is present in the local repo | ARCH-4 (P2) |
| Jackson | two stacks in parallel: `com.fasterxml.jackson` (Jackson 2 — common-core ApiResponse annotations, Jackson2ObjectMapperAutoConfiguration, JsonKafkaSerializer) and `tools.jackson` (Jackson 3 — gateway error writer, kafka wire mapper, Boot 4 default) | Deliberate dual-stack; risk集中 in the deprecated serializer (ARCH-5) |
| spring-retry | declared in `utils/common-kafka/pom.xml` but `org.springframework.retry` / `RetryTemplate` / `@Retryable` appear in **zero** main sources (grep) | dead dependency (P3) |
| SNAPSHOT coupling | all internal versions `1.0-SNAPSHOT`, `flattenMode=resolveCiFriendliesOnly` | monorepo-consistent; no external snapshot consumers |
| ModelMapper + MapStruct | both present (common-spring carries modelmapper optional + mapstruct; services use both styles) | duplication, not a conflict |
| WireMock / testcontainers / h2 | test scope only, consistent versions via Boot/BOM | OK |

## 4. `utils/common-patterns` — investigation result (audit question 3)

Verified facts (git read-only + filesystem):

- `utils/common-patterns/` contains exactly one file on disk: `.flattened-pom.xml` (untracked
  build residue describing a `common-patterns` artifact with test-scoped `javaparser-core`)
  plus a stale untracked `target/` tree
  (`target/test-classes/com/shop/common/patterns/FleetPatternComplianceTest.class`,
  surefire reports, maven-status). `find utils/common-patterns -type f -not -path '*/target/*'`
  → 1 file. `git ls-files utils/common-patterns/` → **0 tracked files**.
- History: introduced by `74ac1431` ("FleetPatternComplianceTest (R1-R6) + PATTERNS.md"),
  deleted by `5d6ba251` ("feat(quality-gate): replace common-patterns with Checkstyle and
  SonarQube") which removed `utils/common-patterns/pom.xml` (45 lines) and
  `FleetPatternComplianceTest.java` (315 lines) and dropped the module from
  `utils/pom.xml`.
- Consumers: **none**. The only references to `common-patterns` in tracked files are
  documentation (`README.md:612` still advertises it as a live module; a plan doc still
  tells agents to run `-pl utils/common-patterns test`, which cannot succeed). No pom
  depends on the artifact.
- Verdict: **ghost module — deleted code, not dead-but-wired code.** The R1–R6 rules it used
  to enforce survive only as 4 Checkstyle regex rules, and the Checkstyle config
  suppresses all structural rules for every module (ARCH-6/ARCH-17).

## 5. Compile-time coupling between services (audit question 1)

- Repo-wide grep for `import com.shop.<otherservice>...` across all modules including tests:
  **zero cross-service imports**. Service boundaries are real at compile time.
- Database coupling: every service has its own logical database
  (`docker-compose.yml` + `k8s/microservices.yaml` datasource URLs listed in
  actual-system-map.md §1). No service yml points at another service's DB. Cross-service
  reads go through HTTP clients (`order-service/.../client/*ServiceClient.java`) or Kafka.
- Shared schema vocabulary only via `common-core` (`ErrorCode`, `ApiPaths`, `OutboxStatus`,
  `KafkaTopicContract` in common-kafka) — the deliberate shared kernel; see ARCH-11.
