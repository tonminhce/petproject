# Infrastructure and Configuration Review: Petproject Platform

- Reviewer: Senior Reviewer (DevOps / Java, Read-Only Audit)
- Date: 2026-09-02
- Scope: Root `pom.xml`, `docker-compose.yml`, `docker-compose.prod.yml`, `start-docker.sh`, `stop-docker.sh`, `mock-services/mock-payment-provider/*`, `docker/**`, `.env*`, `.gitignore`, `.dockerignore`, `lombok.config`, `README.md`, `docker-README.md`, `docs/scripts/*`. Inspected approximately 21 configuration and manifest files plus service POMs and application configurations for consistency checks.

## Baseline Verification Checks

- `.env` is **not** tracked by git (`git ls-files` only shows `.env.example` and `.env.prod.example`); `git log --all -- .env` is empty, confirming it was never committed. `.gitignore` correctly ignores `*.env.*` with exceptions for example files. No secrets were leaked into git history.
- `docs/PRODUCTION-READINESS.md` exists and accurately describes the production overlay setup (running both compose files, `--scale mock-payment-provider=0`, and IP allowlists).

## Findings by File

### pom.xml (root)

- [Medium][pattern] `pom.xml:73,187-190`: `spring-boot-starter-aop` is pinned to `4.0.0-M2` (a milestone release) in repository `dependencyManagement`, overriding the parent Spring Boot managed version `4.1.1`. Any service depending on starter-aop runs an older milestone than Boot itself. Recommendation: Remove the pin to inherit the parent version.
- [Medium][performance] `pom.xml:347-366`: Jib configures only one jvmFlag (`/dev/./urandom`) without `-XX:MaxRAMPercentage` or `-Xmx`. Combined with Docker Compose missing `mem_limit`, the JVM defaults to 25% of host memory per container. 15 Java containers running concurrently can trigger host out-of-memory errors. Additionally, Elasticsearch is pinned to 512m heap in development and inherited directly by the production overlay. Recommendation: Add `-XX:MaxRAMPercentage=75` in Jib and specify `mem_limit` and `cpus` in compose definitions.
- [Medium][security] `pom.xml:349-366`: Jib container configuration omits `<user>`, so all 14 service containers run as **root**. If an attacker achieves remote code execution in one service, they gain root access inside the container, potentially reading environment secrets and moving laterally across internal services. Recommendation: Specify a non-root user (e.g., `65532:65532`) or set `runAsNonRoot`.
- [Low][clean-code] `pom.xml:40,86-118`: The property `<revision>1.0-SNAPSHOT</revision>` is declared but never referenced, while versions are hardcoded as `1.0-SNAPSHOT`. The flatten plugin specifies `resolveCiFriendliesOnly`, rendering it inert. Recommendation: Reference `${revision}` consistently or drop the property.
- [Low][clean-code] `pom.xml:196-201`: Testcontainers BOM import hardcodes `2.0.5` instead of referencing `${testcontainers.version}` from properties. Recommendation: Reference the property.
- [Low][pattern] `pom.xml:294-301`: Checkstyle plugin is only defined in `pluginManagement` with `failsOnError=false` and `failOnViolation=false`, lacks a rule configuration file, and is not bound to module builds. Recommendation: Bind checkstyle with enforceable rules and `failOnViolation=true`, or remove the definition.
- [Low][performance] `pom.xml:362`: Setting `creationTime: USE_CURRENT_TIMESTAMP` prevents reproducible container builds because every build generates new layer digests. Recommendation: Pin timestamps using `SOURCE_DATE_EPOCH` for reproducible builds.

### docker-compose.yml

- [High][pattern] `docker-compose.yml:628` and `notification-service/src/main/resources/application.yml:7-8`: `notification-service` is missing the `*pg-creds` anchor (it specifies only `<<: *jwt`), whereas every other database-backed service receives `POSTGRES_USER` and `POSTGRES_PASSWORD`. In development, this works coincidentally because defaults match `admin/admin`. In production with rotated credentials, `notification-service` falls back to `admin/admin`, fails PostgreSQL authentication, and crashes silently. Recommendation: Add `*pg-creds` to the notification service stanza and remove the `:admin` fallback in `application.yml`.
- [Medium][security] `docker-compose.yml:52-53,71-72,90-91,126-127,150-152`: Infrastructure ports are bound to host `0.0.0.0`: PostgreSQL (`admin/admin`), Redis (password `admin`), Kafka PLAINTEXT without authentication, Elasticsearch with security disabled, and RustFS console (`admin/admin`). On shared networks or VPNs, callers can connect directly to port 5432 with default credentials. Recommendation: Bind to `127.0.0.1:${PORT}` by default and require strong credentials via `.env`.
- [Medium][security] `docker-compose.yml:25-28,45-63` and `docker/postgres/init/create-all-databases.sql`: All 12 service databases share a single superuser account (`POSTGRES_USER`) without role isolation. A SQL injection vulnerability in any one service allows full access to the other 11 databases and the Keycloak realm. Recommendation: Update the initialization script to create isolated roles per database following least privilege principles.
- [Medium][security] `docker-compose.yml:174`: Keycloak runs `start-dev` in development, and `docker-compose.prod.yml` overrides only admin credentials rather than the startup command. Running Keycloak in development mode in production introduces performance degradation and dev artifacts. Recommendation: Use `start --optimized` in the production overlay with dedicated configuration.
- [Medium][security] `docker-compose.yml:392-401` and `docker-compose.prod.yml:30-31`: Mock payment provider is defined in the base compose file and inherited by production, where `PAYMENT_PROVIDER` defaults to `mock`. If operators forget `--scale mock-payment-provider=0`, real payment calls route to the mock simulator. Recommendation: Assign mock services to `profiles: ["dev"]` and require explicit payment provider configuration in production.
- [Medium][pattern] `docker-compose.prod.yml:34-39,73,121,127,142,148,154,162,170,181`: 8 services append JSONL output into a single shared file (`/var/log/audit/audit.jsonl`) on a common volume, risking interleaved log entries and corrupted lines during concurrent writes. Recommendation: Have each service write to a dedicated sub-directory with automated log rotation.
- [Low][security] `docker-compose.yml:70,76`: Redis credentials pass through command line flags (`--requirepass "${REDIS_PASSWORD}"`) and healthchecks, remaining visible in process tables and `docker inspect`. Recommendation: Mount a Redis configuration file or suppress CLI password flags.
- [Low][dry] `docker-compose.yml:216-224` and `.env.example:27-35`: Gateway rate limiting variables declare duplicate default values in both files, requiring manual synchronization. Recommendation: Define defaults in one place.

### docker/keycloak/import/ecommerce-realm.json

- [High][security] `docker/keycloak/import/ecommerce-realm.json:64,77,90,103,127,136`: The realm import file contains static credentials tracked in git: 4 client secrets (`changeme`), user `adminuser/adminpass` (ADMIN and MANAGER roles), and `testuser/testpass`. If public access is enabled and `ADMIN_IP_ALLOWLIST` is left empty, attackers can use default accounts to obtain admin tokens. Recommendation: Do not track default user passwords in realm files; rotate credentials on startup and fail-fast if default passwords remain active.
- [Medium][security] `docker/keycloak/import/ecommerce-realm.json:35-48`: The `ecommerce-client` definition enables direct access grants, full scope permissions, localhost redirect URIs, and wildcard web origins (`*`). Recommendation: Separate dev and production client definitions, restricting production origins to verified domains.

### docker-compose.prod.yml

- [Positive]: Ingress-only architecture (`ports: !override []`), fail-closed validation for empty secrets, and documented reverse proxy forwarding headers are properly structured.
- [Medium][security] `docker-compose.prod.yml:56` and `.env.prod.example:31`: `ADMIN_IP_ALLOWLIST` defaults to empty (which leaves the filter inactive). Recommendation: Fail-fast if the allowlist variable is empty in production rather than allowing all incoming traffic.
- [Low][documentation] `docker-compose.prod.yml:65`: The comment instructs operators to copy the OTLP export line into every service block. Recommendation: Configure OpenTelemetry endpoints globally via host environment variables rather than duplicate block definitions.

### start-docker.sh / stop-docker.sh

- [Medium][documentation] `start-docker.sh:211,221-226`: The summary table reports Keycloak Admin on `http://localhost:8080`, which conflicts with the gateway port (Keycloak publishes on port 9090). Recommendation: Correct the summary output to reflect port 9090 and read connection secrets dynamically from `.env`.
- [Low][error-handling] `stop-docker.sh:149`: The `down` command specifies `--env-file ${ENV_FILE}`. If an operator removes `.env` while the stack is active, the stop command fails. Recommendation: Remove the env-file requirement from teardown commands.
- [Positive]: `stop-docker.sh:112-127` blocks destructive volume deletion in non-interactive CI environments unless `FORCE=1` is provided.
- [Low][performance] `start-docker.sh:128`: Always triggers `mvn -q -DskipTests jib:dockerBuild`, taking extra build time even when container images already exist. Recommendation: Add a `--no-build` flag.

### mock-services/mock-payment-provider/

- [Medium][error-handling] `server.js:22-44,58`: Webhooks fire once with a random delay (200-800ms) without retry policies or timeouts. If payment-service restarts during deployment, the event is permanently dropped. Recommendation: Implement basic retry logic with backoff.
- [Low][security] `server.js:86-89`: Incoming POST request bodies accumulate in memory without bounds checking. Recommendation: Enforce a 1 MB payload size limit using `req.destroy()`.
- [Low][security] `Dockerfile:1`: The container runs as root. Recommendation: Switch to `USER node`.

### .env / .env.example / .env.prod.example / .gitignore / .dockerignore

- [Low][documentation] `.env.example`: Missing several environment variables utilized by compose definitions (`KAFKA_SERVERS`, `PAYMENT_*`, `SHIPMENT_*`, `SMTP_*`, `SEARCH_SERVICE_CLIENT_*`). Recommendation: Keep `.env.example` in sync with all supported configuration keys.
- [Low][documentation] `.env:45`: `STORAGE_BUCKET=ecommerce-media` differs from the `media` default used in compose definitions. Recommendation: Standardize bucket naming across files.
- [Low][clean-code] `.env:31` and `.env.prod.example:66`: Configures `KEYCLOAK_CLIENT_SECRET` for `ecommerce-client`, which is a public client without a secret. Recommendation: Remove this redundant variable from templates.

### README.md / docker-README.md

- [Medium][documentation] `docker-README.md:29,78`: Mentions Keycloak Admin at `http://localhost:8080` instead of port 9090. Recommendation: Update port reference to 9090.
- [Medium][documentation] `README.md:12`: Lists Kafka 4.x whereas the compose setup runs `apache/kafka:3.9.0`. Recommendation: Standardize the version note to Kafka 3.9 (KRaft).
- [Low][documentation] `docker-README.md:71`: States 20 containers total, while compose defines 21 including the mock payment provider. Recommendation: Update the container count.

### docs/scripts/

- [Low][error-handling] `docs/scripts/e2e-test.sh:80-88`: JSON parsing with `python3 -c` lacks error handling guards. If a service returns non-JSON or fails, the script exits abruptly without a summary. Recommendation: Add response validation guards.
- [Low][dry] `docs/scripts/e2e-test.sh` and `e2e-favourite-inventory.sh`: Helper functions (`assert_http`, `assert_contains`, `curl_code`) are duplicated across scripts. Recommendation: Consolidate shared testing helpers into a common shell library.

## Findings Summary Matrix

| Severity \ Category | pattern | security | performance | documentation | clean-code | dry | error-handling | Total |
|---|---|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 1 | 1 | 0 | 0 | 0 | 0 | 0 | **2** |
| Medium | 2 | 6 | 1 | 3 | 0 | 0 | 1 | **13** |
| Low | 1 | 4 | 1 | 4 | 3 | 2 | 1 | **17** |
| **Total** | **4** | **11** | **2** | **7** | **3** | **2** | **2** | **32** |

## General Maturity Assessment

The infrastructure setup demonstrates solid foundational engineering for development and pre-production environments: YAML anchors minimize duplication, all 21 containers configure proper healthchecks and dependency orders, the production overlay implements an ingress-only model, and operational scripts incorporate confirmation prompts before data deletion. Primary improvement areas include credential rotation between development and production, enforcing memory and CPU limits, and resolving configuration discrepancies between documentation and compose definitions.
