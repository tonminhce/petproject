# Independent QA review — orchestrator change set

- **Reviewer**: independent QA / test engineer (not an implementer of this change set)
- **Date**: 2026-11-20
- **Repository**: `/Users/tonminh-mac/IdeaProjects/untitled5`
- **Scope**: the orchestrator's own changes — alerting pipeline, load-testing harness, Stripe verification harness, Kubernetes manifest validator, harness gate logic, CI workflow, final-report generator, YAML subset parser.
- **Method**: static verification only (read / grep / glob) plus cheap read-only commands. No Maven build, no Maven test, no long-running test was executed (three implementation agents are compiling against the local Maven repository in parallel).
- **Commands actually executed** (all read-only, none mutating the repository):
  - `python3 -m unittest harness.tests.test_alerts` (19 tests) and `harness.tests.test_stripe` (13 tests) — the two *new* test modules only.
  - `harness.alerts.validation.validate_alerting()` and `harness.kubernetes.validation.validate_manifests()` called directly as functions (no CLI entry point, therefore no state write).
  - `harness.yamlmini.loads/load_all` over every YAML file in `k8s/` and `config/observability/`.
  - `harness.inventory.scanner.source_fingerprint()` + `harness.verification.gates.evidence_is_fresh()` read-only simulation.
  - `git ls-files`, `git status`, `git diff` (read-only) and an independent credential scan over tracked **and** untracked files.

---

## Summary of verdicts

| # | Area | Verdict |
|---|------|---------|
| 1 | Alertmanager config (routing, grouping, inhibition, receivers, templates) | **PARTIAL — PASS on route separation, FAIL on the incident contract and on dead CI routing** |
| 2 | Runtime alert rules | **FAIL — 13 of 23 rules can never fire on this platform** |
| 3 | Credential hygiene | **PASS with caveats** (no usable credential committed; the automated scan has blind spots and scanned none of the new files) |
| 4 | Compose rendering of the Alertmanager config | **FAIL — awk substitution hazard on an unvalidated value; the inactive template is never machine-validated** |
| 5 | Load harness (limits, contamination guard, invariant SQL) | **FAIL — one invariant is permanently vacuous, one is permanently false-failing, the runner reports PASS on contaminated/invalid evidence** |
| 6 | Stripe harness (classification, no fabrication, webhook scheme) | **PARTIAL — the no-fabrication property holds; the classification and the webhook test do not** |
| 7 | YAML subset parser | **PASS for today's repository content — one latent silent-drop false-PASS risk remains** |
| 8 | Gate staleness logic | **PASS — stale and pre-fingerprint evidence are correctly refused; three adjacent weaknesses remain** |
| 9 | Final report generator | **FAIL — hardcoded success statements and no staleness filter** |
| 10 | CI workflow | **FAIL — the pipeline is red on arrival; three jobs fail deterministically** |

**Final verdict: `QA_FAIL`** (see the end of this document).

---

## 1. Alertmanager configuration — `config/observability/alertmanager/alertmanager.yml.template`

### What is correct

- **Routing separation is sound.** Child routes are evaluated in order and the first match wins. The CI route (`category="ci"`) is declared **first** (lines 13–23), before the `severity="critical"` and `severity="warning"` routes.
  - A runtime alert can **never** be swallowed by the CI route: no runtime rule sets `category="ci"`. Verified against all 23 rules — the categories actually emitted are `application, infrastructure, inventory, kafka, payment, promotion` (`runtime-alerts.yml`, confirmed by `harness alerts validate` → ALT-02 evidence).
  - A CI alert can **never** be swallowed by a runtime route **as long as it carries `category="ci"`**, because the CI route is matched first even when the alert also carries `severity="critical"`.
  - Alerts with no `severity` label fall through to the root route → `telegram-runtime-warning` with the root `group_by`/`group_interval`/`repeat_interval`. That is a sane default.
- **Grouping parameters are internally consistent.** The root `group_by: [alertname, application, environment]` is inherited by the two runtime sub-routes (they do not redefine it); the CI route redefines it as `[alertname, repository, workflow]` with `group_wait: 0s`, `group_interval: 1m`, `repeat_interval: 30m`; the root keeps `group_wait: 30s`, `group_interval: 5m`, `repeat_interval: 4h`.
- **Inhibition rules are valid Alertmanager 0.22+ syntax** (`source_matchers` / `target_matchers`), the source/target labels are disjoint (`critical` → `warning`), and `equal: [alertname, application, environment]` is a sensible key set. The second rule (ServiceDown suppresses warnings for the same application) is a deliberate broadening of the first.
- **The message templates expose every field of the incident contract.** Both runtime templates (lines 60–73 and 80–93) render: Severity, Environment, Service, Issue (`.CommonAnnotations.summary`), Started (`.StartsAt`), Impact, Current metric, Runbook, Grafana, Logs, Traces, Status. **All 12 required fields are present.**
- **The resolved notification path is present**: `send_resolved: true` on all three receivers, and `{{ if eq .Status "resolved" }}` renders the resolved status. `global.resolve_timeout: 5m` is set.
- `harness alerts validate` ALT-01 = PASS (3 telegram receivers, CI route found, 2 inhibit rules, `group_by` present, no committed token).

### Defects

**A-1 (HIGH) — the "Service" field of the incident contract is empty for every `up`-based alert.**
`ServiceDown` (`up{job="petproject-services"} == 0`), `DatabaseUnavailable` (`up{job="postgresql"} == 0`) and `RedisUnavailable` (`up{job="redis"} == 0`) evaluate on Prometheus' own `up` series, whose label set is exactly `{job, instance}` (+ the external label `environment`). They carry **no `application` label**. The runtime message template renders `{{ .CommonLabels.application }}` → **empty string**. The operator receives "Service: " with no value; only the annotation `summary` (`Service {{ $labels.instance }} is unreachable`) identifies the instance. The same three alerts are the *only* ones a responder most needs to route correctly.
Additionally, because `group_by` includes `application`, all 14 `ServiceDown` alerts collapse into **one** group (`alertname=ServiceDown`, `application=""`, `environment=petproject`) → one Telegram message listing 14 instances instead of 14 actionable messages.

**A-2 (MEDIUM) — inhibition rule 2 can never match.**
`ServiceDown` has an empty `application` label while the warning targets carry a populated one; `equal: [application, environment]` therefore never holds. The intended "service down suppresses its own warnings" behaviour does not exist. (Rule 1 still works when source and target share an alertname.)

**A-3 (HIGH) — the CI route and the `telegram-ci` receiver are dead configuration.**
The CI notification path does **not** go through Alertmanager: `scripts/notify-telegram.sh` posts directly to `https://api.telegram.org/bot<token>/sendMessage`. Nothing in the repository posts an alert to Alertmanager with `category="ci"` (the only Alertmanager API writer is the harness dedup probe, which uses `category="harness"`). Consequences:
- the `telegram-ci` receiver and the CI route are unreachable;
- the CI path and the runtime path are **not** actually separated by Alertmanager — they are two independent, differently-wired pipelines, and the routing tree gives a false impression of a single governed pipeline.

**A-4 (MEDIUM) — the CI route's `group_by` uses a label the CI producer never sets.**
The CI route groups by `repository`/`workflow`, but the CI message template renders `{{ .CommonLabels.project }}` and the script labels the value `project` (not `repository`). If CI alerts were ever routed through Alertmanager, `repository` would be empty and all CI alerts would collapse into one group.

**A-5 (LOW) — the two runtime receivers are byte-identical.** `telegram-runtime-critical` and `telegram-runtime-warning` differ only in name; the severity is already in the payload, so this is duplication without behavioural difference. Not a correctness defect.

---

## 2. Runtime alert rules — `config/observability/alertmanager/rules/runtime-alerts.yml`

### The decisive finding: 13 of 23 rules reference metrics that do not exist in this platform

I searched the entire first-party source tree (excluding `target/`) for every metric name used by the rules. The platform's real custom metrics are named differently:

| Rule | Metric used | Reality in the code | Verdict |
|------|-------------|----------------------|---------|
| `ApplicationStartFailure` | `application_startup_failures_total` | no such counter anywhere | **never fires** |
| `KafkaConsumerLagHigh` | `kafka_consumergroup_lag` | no JMX exporter in the stack; Micrometer only emits `kafka_producer_*`/`kafka_consumer_*` | **never fires** |
| `KafkaDeadLetterGrowth` | `kafka_dead_letter_events_total` | not registered | **never fires** |
| `OutboxPublisherFailure` | `outbox_publish_failures_total` | not registered | **never fires** |
| `OutboxBacklogGrowing` | `outbox_pending_events_count` | real gauges are `product_outbox_pending_count`, `order_outbox_pending_count`, `inventory_outbox_pending_count`, `promotion_outbox_pending_count` (e.g. `OrderMetrics.java:24`, `InventoryMetrics.java:25`) | **never fires** |
| `StripeWebhookFailure` | `payment_webhook_processing_failures_total` | not registered | **never fires** |
| `InvalidWebhookSignatureSpike` | `payment_webhook_invalid_signature_total` | not registered | **never fires** |
| `PaymentOrderDivergence` | `payment_order_divergence_total` | not registered | **never fires** |
| `RefundProcessingFailure` | `payment_refund_failures_total` | not registered | **never fires** |
| `InventoryInvariantViolation` | `inventory_invariant_violations_total` | not registered | **never fires** |
| `CouponRedemptionInconsistency` | `promotion_coupon_redemption_conflicts_total` | not registered | **never fires** |
| `PromotionEvaluationErrorSpike` | `promotion_evaluation_errors_total` | not registered | **never fires** |
| `CpuSaturation` | `process_cpu_limit` | Micrometer exposes `process_cpu_usage` but **no** `process_cpu_limit` | **never fires** |

Only 10 rules are reachable: `ServiceDown`, `HighHttp5xxRate`, `HighRequestLatency`, `CircuitBreakerOpen`, `DatabaseUnavailable`, `DatabasePoolSaturation`, `RedisUnavailable`, `SearchDependencyFailure`, `StorageDependencyFailure`, `HighMemoryPressure`.

This is the classic **silent false negative**: `increase(<absent metric>[5m]) > 0` evaluates to an empty vector, so the rule is permanently "ok". `harness alerts validate` ALT-02 passes because it only checks that every rule carries a `severity`, `summary`, `metric` and `runbook` and that the category set is covered — **it never checks that the metric exists**. The validator therefore reports PASS for a rule file in which 56 % of the rules are dead.

### Label analysis against the real metrics

- The Spring Boot Actuator metrics do carry `application`: `utils/common-spring/src/main/resources/application.yml:55-56` sets `management.metrics.tags.application: ${spring.application.name}`, and the per-service `spring.application.name` values are `order-service`, `payment-service`, `search-service`, `media-service`, … So `application="search-service"` (SearchDependencyFailure) and `application="media-service"` (StorageDependencyFailure) match the real values.
- `http_server_requests_seconds_count{status="5xx"}` — Micrometer's `http.server.requests` carries `status`; the histogram buckets required by `HighRequestLatency` require `management.metrics.distribution.percentiles-histogram.http.server.requests: true` (`common-spring application.yml:50-54`) (same config-loading caveat as below).
- `resilience4j_circuitbreaker_state{state="open"}` — Resilience4j is on the classpath and is actually used (`@CircuitBreaker` in `rating-service/.../EligibilityClient.java:34` and `media-service/.../MediaReferenceClient.java:31`); the Micrometer binder emits `resilience4j_circuitbreaker_state{name,state}`, and the annotation uses `{{ $labels.name }}`.
- `hikaricp_connections_active` / `hikaricp_connections_max` — both exist in Micrometer's Hikari metrics with the same tag set (`pool`), so the `and` matches.
- `jvm_memory_used_bytes{area="heap"}` / `jvm_memory_max_bytes{area="heap"}` — both exist, **but** Micrometer 1.10+ adds an `id` label (per memory pool: `G1 Eden Space`, `G1 Old Gen`, …). The expression therefore evaluates **per pool**, not per heap: an Eden-space ratio above 0.85 fires a heap-pressure WARNING even when total heap usage is healthy → **noise / false positive** (MEDIUM). It also silently never fires when `jvm_memory_max_bytes` is absent (no explicit `-Xmx`; the compose sets `-Xmx384m` so it is present in compose).

### False positives found

- **R-1 (MEDIUM-HIGH) — `HighHttp5xxRate` has no minimum-traffic guard.** `(sum 5xx rate) / clamp_min(sum all rate, 0.001) > 0.05` fires whenever 5 % of a *tiny* traffic volume is a 5xx. One 5xx in a service that handles one request every few minutes yields a ratio of 1.0 → a **critical** page after `for: 3m`. `clamp_min(...)` protects against division by zero but not against statistical noise.
- **R-2 (MEDIUM) — `SearchDependencyFailure` / `StorageDependencyFailure` fire on a single 5xx.** `rate(...{status=~"5.."}[5m]) > 0` means "at least 1 failure in the window". With a 5-minute rate window and `for: 2m`, a single transient 5xx is enough to page. They also duplicate `HighHttp5xxRate` for the same two services, and their wording is wrong: the expression measures the **service's own** HTTP 5xx responses, not the health of the search backend or the object store ("product search is degraded because the search backend is failing" is not what the expression proves).
- **R-3 (LOW) — monitoring blind spot.** There is no dead-man's-switch / watchdog alert for the monitoring stack itself: if Prometheus is down, nothing fires at all.
- **R-4 (MEDIUM) — the OpenTelemetry metrics path is not consumed.** `config/observability/otel-collector-config.yaml` exports application metrics through the `prometheus` exporter on **:8889**, while `prometheus.yml:8-10` scrapes `otel-collector:8888`, which is the collector's *own* telemetry reader. The OTel-derived application metrics never reach Prometheus.

### Scrape targets that really exist (confirmed)

- `job_name: petproject-services` → 14 service targets; the ports match the compose service definitions (`gateway-service:8080`, `auth-service:8088`, …).
- `job_name: postgresql` → `postgres-exporter:9187` — the compose service `postgres-exporter` (`prometheuscommunity/postgres-exporter:latest`, `DATA_SOURCE_NAME` pointing at `postgres:5432/ecommerce`, `PG_EXPORTER_AUTO_DISCOVER_DATABASES=true`) exists and listens on the default 9187.
- `job_name: redis` → `redis-exporter:9121` — the compose service `redis-exporter` (`oliver006/redis_exporter:latest`, `REDIS_ADDR: redis://redis:6379`) exists and listens on 9121.
- `rule_files: /etc/prometheus/rules/*.yml` is mounted from `./config/observability/alertmanager/rules` (compose line 674), and `alerting.alertmanagers → alertmanager:9093` matches the compose service.

---

## 3. Credential hygiene

### Verified positively (independent check)

I scanned **every** file in the repository — tracked (1503), untracked (78) and the gitignored `.env` — with the patterns
`[0-9]{6,12}:[A-Za-z0-9_-]{30,}`, `sk_live_*`, `sk_test_*`, `rk_live_/rk_test_*`, `whsec_[A-Za-z0-9]{16,}`.

- **No usable credential is committed.** The only matches are deliberately fake literals: `ci.yml` (`123456789:AAEciValidationTokenValueOnlyForParsing`, used to make `amtool check-config` parse a syntactically valid file), `test_alerts.py` (`123456789:AAEtest…`) and `test_stripe.py` (`sk_live_definitely_not_allowed`).
- The local `.env` is **not tracked** (`.gitignore:22-23` → `.env`, `.env.*`; `git ls-files .env` is empty).
- The Alertmanager template contains **placeholders only** (`__TELEGRAM_BOT_TOKEN__`, `__TELEGRAM_CHAT_ID__`, `__TELEGRAM_CI_CHAT_ID__`).
- The compose service takes the token from the **environment at start time** (`environment: TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN:-}`) and renders it **inside the container** into `/tmp/alertmanager.yml` — no credential file is ever written to the host.
- The CI workflow reads the token from **repository secrets** (`${{ secrets.TELEGRAM_BOT_TOKEN }}`) and passes it through `env:` (not on the command line). No job prints it; the script keeps it in the URL only and `curl --fail-with-body` output is discarded on failure, so the token does not reach the log.
- The **token format guard** exists on the compose path and does reject a malformed token *before* rendering: `printf '%s' "$TOKEN" | grep -Eq '^[0-9]{6,12}:[A-Za-z0-9_-]{20,}$' || { echo …; exit 1; }` (compose lines 724-725). The Python path is equally strict (`BOT_TOKEN_PATTERN` in `telegram/client.py:11`, enforced by `validate_bot_token`).

### Caveats (defects)

- **C-1 (HIGH) — the rendered configuration file is not git-ignored.** `harness alerts render` writes the live bot token to `config/observability/alertmanager/alertmanager.yml` (`alerts/render.py:34-42`, chmod 0600). `.gitignore` contains **no** rule for that path (verified by grep). The file is therefore an untracked-but-not-ignored secret sitting in the working tree, one `git add .` away from a commit. The harness even prints "the rendered file holds the live credential and must stay out of git" — but nothing enforces it.
- **C-2 (MEDIUM) — the harness' own secret scan cannot see this change set.** ALT-06 uses `git ls-files` (tracked only). **None** of the new files (`alertmanager.yml.template`, `alertmanager.inactive.yml.template`, `runtime-alerts.yml`, `scripts/notify-telegram.sh`, `load-tests/k6-scenario.js`, `production-audit/harness/{telegram,stripe,alerts,kubernetes,loadtesting}`, `yamlmini.py`, the two new test modules) is tracked yet — `git ls-files config/observability/alertmanager scripts/notify-telegram.sh load-tests/k6-scenario.js production-audit/harness/telegram production-audit/harness/stripe production-audit/harness/yamlmini.py` returns **nothing**. The ALT-06 PASS therefore certifies 1503 pre-existing files and **zero** files of the change under review.
- **C-3 (MEDIUM) — the secret-scan patterns have blind spots.** `TELEGRAM_TOKEN_LITERAL` requires the literal `TELEGRAM_BOT_TOKEN` before the value, so a token committed under any other name (e.g. `bot_token: 123456789:AAE…`) is not detected — the CI workflow file itself contains exactly such a literal that the scanner does not flag. `LIVE_STRIPE_KEY` only matches `sk_live_*`, so a committed **`sk_test_*`** key (still a usable credential) is not detected. `TEMPLATE_SUFFIXES` omits `.json`, `.md`, `.tf`, `.conf`, `.txt`, so tokens in those file types are invisible.
- **C-4 (LOW) — `redact()` (`telegram/client.py:118-129`) reads `os.environ` directly**, so a redaction performed against an injected `env` dict can still leak a token that only exists in that dict.

---

## 4. Compose rendering — `docker-compose.yml:712-743`

### Correct

- **The entrypoint override is right.** `prom/alertmanager` has a non-shell entrypoint (`/bin/alertmanager`). The service sets `entrypoint: ["/bin/sh", "-c"]` and passes the script as `command:`, so Docker executes `/bin/sh -c "<script>"`. The prometheus images are busybox-based, so `/bin/sh`, `grep -E` and `awk` are available. (Not runtime-verified here: the Docker daemon was not reachable in this environment.)
- The rendered file is written to `/tmp/alertmanager.yml` **inside the container** and Alertmanager is exec'd on it — no credential materialises on the host.
- The empty-token path copies `alertmanager.inactive.yml.template` and Alertmanager starts in a "no notification" mode instead of crash-looping.

### Defects

- **D-1 (HIGH) — the awk substitution can corrupt the config, and the value that can corrupt it is not validated.**
  The render step is
  `awk -v t="$TOKEN" -v c="$CHAT" -v ci="$CI" '{ gsub("__TELEGRAM_BOT_TOKEN__", t); gsub("__TELEGRAM_CHAT_ID__", c); gsub("__TELEGRAM_CI_CHAT_ID__", ci); print }'`.
  In awk, `&` inside a `gsub` replacement is special: it expands to the **matched text**. The bot token is protected because the format guard restricts it to `[0-9]{6,12}:[A-Za-z0-9_-]{20,}` (no `&`), but **`TELEGRAM_CHAT_ID` and `TELEGRAM_CI_CHAT_ID` are substituted with no format check at all**.
  **Exact failure mode**: if a chat id contains `&` (e.g. `TELEGRAM_CHAT_ID="-100&123"`), awk substitutes the *placeholder text itself* for the `&`, so the rendered file contains `chat_id: __TELEGRAM_CHAT_ID__`. That is still **valid YAML**, so Alertmanager starts normally, accepts the configuration, and then fails every send with a "chat not found" error — a silently dead notification path with no startup signal. The same class of bug applies to a chat id containing a newline (awk's `-v` performs escape processing, so a backslash-n becomes a real newline, splitting the value across lines and making the file unparseable → Alertmanager crash-loop).
  The Python renderer (`alerts/render.py:21-26`) does **not** have this problem — it uses `str.replace`, which treats `&` literally — so the two renderers have different semantics for the same input. The chat-id validation exists in Python (`validate_chat_id`, `^-?[0-9]{1,20}$`) but is not applied on the compose path.
- **D-2 (MEDIUM) — a partially configured environment yields a valid-but-dead Alertmanager.** The compose guard validates only the bot token. With `TELEGRAM_BOT_TOKEN` set but `TELEGRAM_CHAT_ID` empty, the runtime receivers render `chat_id:` (empty) while the CI receiver may be fine → runtime alerts are silently dropped. The Python path fails closed (`validate_chat_id` raises on empty) — the compose path does not.
- **D-3 (MEDIUM) — the inactive template is never machine-validated.** Neither the CI workflow nor the harness validates `alertmanager.inactive.yml.template`; the CI job only `amtool check-config`s the **active** template. I verified the inactive template statically: it parses with the repository's own parser, it declares `receiver: telegram-disabled` at the root and in all three sub-routes, and it defines a single `receivers: [{name: telegram-disabled}]` entry, which is a structurally valid Alertmanager configuration. **But** nothing in the pipeline proves this, so a future edit that breaks it would only surface as a container that refuses to start.
- **D-4 (LOW) — the render step does not verify the output.** The script `exec`s Alertmanager without checking that the rendered file parses; a corrupted render (see D-1) is discovered only by Alertmanager's own start-up error, and only for hard YAML errors.

---

## 5. Load harness — `production-audit/harness/loadtesting/*`, `load-tests/k6-scenario.js`

### What is correct

- **The safety limits are enforced before the run.** `enforce_safety()` (`profiles.py:145-169`) checks rps <= 500, VUs <= 500, duration <= 1800 s and planned volume <= 5 000 000, and `run_profile()` returns **BLOCKED** (and `harness load` exits `EXIT_BLOCKED`) when a plan violates them (`runner.py:114-121`). Every profile x allowed scale (`ALLOWED_SCALES = (1, 3, 5, 10)`) is inside the limits or is refused; the runner derives `RATE`/`VUS`/`DURATION` from the **resolved** profile, so the script cannot exceed the cap.
- **The k6 script honours the injected limits** (`k6-scenario.js:6-13, 56-62`): `RATE`, `VUS`, `DURATION` are read from the environment and used to build the stages, and thresholds `http_req_failed: rate<0.05` and `checks: rate>0.90` are declared, so a failing run exits non-zero and is reported as **FAIL**.
- **The schema of most invariants is correct** against the real Liquibase changelogs:
  - INV-01 `inventory.available_quantity / reserved_quantity` — `changelog-001-initial-schema.yaml:8-21`
  - INV-02 `campaign.used_count / max_redemptions` — `max_redemptions` in `changelog-001:23`, `used_count` in `changelog-006-campaign-quota-counters.yaml:10-15`
  - INV-03 `orders.status` — the enum `OrderStatus {PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED}` matches the harness list **exactly** (no false fail from a missing enum value), and `status` is NOT NULL so the `NOT IN` test cannot be defeated by NULL
  - INV-04/INV-05 `payments.order_id / status / idempotency_key / deleted` — all present (`changelog-001-payments.yaml:16-29`)
  - INV-06/INV-08 `coupon_usage_reservation.order_id / status / expires_at` — all present (`promotion changelog-001:52-69`)
  - INV-07 compares `orders.id` (uuid) with `payments.order_id` (uuid) — **the types match**, so the set difference is meaningful (the problem with INV-07 is the status filter, below)

### Defects

- **L-1 (HIGH) — INV-08 is permanently vacuous.** The statement filters `status = 'RESERVED'`, but the promotion service has **no such status**: `UsageStatus {PENDING, COMMITTED, RELEASED, EXPIRED}` (`promotion-service/.../constant/UsageStatus.java:3`) and every write uses one of those four (`CampaignReservationServiceImpl.java:66-159`). The query therefore matches **zero rows, always**, and the invariant reports **PASS** forever. The intended condition ("a reservation past its expiry must not stay held") would be `status = 'PENDING' AND expires_at < now() - interval '15 minutes'`. As written, the invariant can never detect the condition it describes — a permanent false pass.
- **L-2 (HIGH) — INV-07 is a false-failing check for partially refunded payments.** The counterpart query is `SELECT DISTINCT order_id FROM payments WHERE deleted = false AND status IN ('CAPTURED','REFUNDED')`. A partial refund **moves the existing row** from `CAPTURED` to `PARTIALLY_REFUNDED` (`WebhookEventService.applyPartialRefund`, lines 189-200; `PaymentStateMachine` allows `CAPTURED -> {PARTIALLY_REFUNDED, REFUNDED}`), so the row is no longer selected. A perfectly legitimate order in `CONFIRMED/SHIPPED/DELIVERED` with a partially refunded payment is therefore counted as a **payment/order divergence** → a false FAIL that would block a release on correct data. The unused constant `TERMINAL_PAYMENT_STATUSES = ("CAPTURED","REFUNDED","FAILED")` (`invariants.py:12`) shows the intended filter was never wired in; the `previous_status` column (`changelog-001-payments.yaml:20`) holds `CAPTURED` and would make the check correct.
- **L-3 (MEDIUM) — INV-05 and INV-06 are tautological.** INV-05 (duplicate `(order_id, idempotency_key)`) is already enforced by the unique index `uk_payment_idempotency_key` (`payment changelog-001:37`); INV-06 (duplicate `order_id` per reservation) is enforced by `uk_cur_order_id` (`promotion changelog-001:98`). Both can never return > 0, so both always PASS without proving anything about the application logic. (INV-05 is also weaker than its own description: it groups by `(order_id, idempotency_key)`, so the same key reused for a *different* order would not be detected.)
- **L-4 (HIGH) — the invariant set can pass vacuously and the runner still reports PASS.** Every non-counterpart invariant is a `COUNT(*)` over a table: with an empty database (or a load run that never created an order) all counts are 0 → **all invariants PASS** without exercising anything. The counterpart invariant INV-07 has the same property (no confirmed order ⇒ empty expected set ⇒ 0 violations ⇒ PASS). Nothing in `verify()` asserts that the invariant was actually exercised (no row-count or "must be non-empty" precondition). Combined with `command_load`, which returns `EXIT_OK` when the invariant status is **BLOCKED** (not FAIL) — see L-5 — a completely unproductive run can be recorded as a successful load test.
- **L-5 (HIGH) — the runner reports `status: PASS` even when the measurement was invalid or the invariants were blocked.** `run_profile()` sets `"status": "PASS" if result.returncode == 0` and merely annotates `"measurement_valid": not environment.get("contaminated")` (`runner.py:194-212`). It never downgrades the status. In `command_load`, the exit code is `EXIT_GATE_FAILED` only when `invariants.status == "FAIL"`; a **BLOCKED** invariant set (e.g. the `postgres` container unreachable) yields **EXIT_OK**. So a run executed on a contaminated host, or one whose invariants could not be evaluated at all, is published as a PASS load result and persisted to `production-audit/state/performance-baselines.json`.
- **L-6 (MEDIUM) — the contamination guard is sampled at the wrong time and is incomplete.** `host_snapshot()` is taken **before** the k6 run (`runner.py:125`), so it measures the host's load before the load test, not during the measurement window; the concurrent-build check (`pgrep -fl "maven|java.*surefire|java.*maven"`) is a point-in-time sample, and `load_average` is a 1/5/15-minute smoothed value that lags badly. It also cannot detect interference from non-Maven processes. The guard's threshold (load/CPU > 1.5) is reasonable; its *sampling* is not.
- **L-7 (MEDIUM) — `verify(container=...)` ignores its `container` argument.** `invariants.verify()` accepts `container: str = POSTGRES_CONTAINER` but never passes it to `psql()`, which always uses the module constant.
- **L-8 (MEDIUM) — the psql user and database names are hard-coded.** `POSTGRES_USER = "admin"` and the database names (`inventoryservice`, `promotionservice`, `orderservice`, `paymentservice`) are hard-coded. They match the compose `SPRING_DATASOURCE_URL` values today, but a failure to connect surfaces as a **BLOCKED** invariant rather than a configuration error.
- **L-9 (LOW) — `_scalar` returns 0 for an empty result set.** For a `COUNT(*)` there is always one row, so this is only reachable with a malformed statement; the effect would be a silent PASS.

---

## 6. Stripe harness — `production-audit/harness/stripe/sandbox.py`

### Correct

- **The harness refuses to fabricate provider success.** `test_payment()` returns **BLOCKED** unless `classify_key(secret_key) == "test"`; the only PASS path requires **HTTP 200 from the real Stripe API** for both the create and the confirm call (`sandbox.py:214-235`), and the result carries the provider's `request-id` headers as evidence. There is **no** code path that manufactures a successful provider response: a non-200 becomes FAIL, and a network error propagates as an exception rather than a PASS. `reconcile()` likewise requires a 200.
- **The webhook signature scheme is the real Stripe scheme.** `sign_payload()` computes `HMAC-SHA256(webhook_secret, "<timestamp>.<payload>")` and formats it as `t=<ts>,v1=<hex>` — exactly Stripe's documented scheme. `verify_webhook_signature()` parses `t`/`v1`, enforces a 300-second tolerance and compares with `hmac.compare_digest` (constant time), and rejects a tampered payload, a stale timestamp and a malformed header — all three are covered by unit tests that pass.
- The live-key guard is sound: `sk_live_`/`rk_live_` ⇒ `live` ⇒ FAIL, and `test_payment`/`reconcile` refuse anything that is not a test key.

### Defects

- **S-1 (MEDIUM) — the key classification does not separate a genuine test key from a fabricated placeholder.** `classify_key()` is a **prefix test only**: any string starting with `sk_test_` is classified `"test"`. A hand-written `sk_test_fake` passes the classification and is reported as `secret_key_mode: PASS — "the configured Stripe key is a test key"`; only the subsequent API call (`api_authentication`) saves the overall verdict. The webhook secret, by contrast, **is** validated properly (`classify_webhook_secret` requires `whsec_` + 32 alphanumeric) — the two classifications are not symmetric.
- **S-2 (MEDIUM) — `test_webhooks()` passes with a placeholder secret and proves nothing about the system under test.**
  (a) It only checks that the secret is **non-empty** (`sandbox.py:283-284`) — it does **not** call `classify_webhook_secret`. With `whsec_local_e2e_only` (the value the final report itself calls "a local placeholder") the test returns **PASS**.
  (b) The test signs the payload with `sign_payload()` — the very function under test — and then verifies it with `verify_webhook_signature()`. This is a **self-consistency (circular) check**: it can never detect a divergence from Stripe's actual scheme, and it says nothing about the payment service's real webhook endpoint, which is not called at all. A PASS here is not evidence that the platform accepts genuine Stripe webhooks.
- **S-3 (MEDIUM) — `test_payment()` reports PASS for a payment that did not succeed.** `status = "PASS" if confirm_code == 200` — the returned `state` is not checked. A PaymentIntent that comes back `requires_action`, `requires_payment_method` or `requires_capture` is reported as a successful end-to-end payment, and the report then prints the PaymentIntent id and state as evidence of a real payment (`final_report.py:221-223`).
- **S-4 (LOW) — multiple `v1` signatures are not supported.** Stripe may send several `v1=` entries during secret rotation; the header parser keeps only the last one (`parts[name] = value` overwrite), so a header with a valid earlier signature would be rejected.
- **S-5 (MEDIUM) — `load_credentials()` reads the repository's `.env` file.** The harness silently sources credentials from an untracked local file, so the credential under test depends on the developer's workstation. The persisted evidence shows the consequence: `stripe-results.json` records a live HTTP 401 from `api.stripe.com`.
- **S-6 (HIGH, test quality) — the new unit test is not hermetic and one new test fails deterministically.**
  - `test_a_placeholder_webhook_secret_is_rejected` performs a **live HTTPS call to `api.stripe.com`** (observed: HTTP 401 with a ResourceWarning). In a network-restricted runner `_request()` raises an uncaught `URLError` (only `HTTPError` is caught) → the test **errors** instead of failing.
  - `test_the_provider_defaults_to_stripe` **fails today**: it asserts `credentials["sandbox"]` is true, but `SHOP_PAYMENT_STRIPE_SANDBOX`/`STRIPE_SANDBOX` is **not present in `.env`, not present in `.env.example`, and not set in the environment** → `sandbox` is `False`. Reproduced: `FAILED (failures=1)`. This is a deterministic CI failure (see item 10).

---

## 7. YAML subset parser — `production-audit/harness/yamlmini.py`

### Verified against the actual repository content

I parsed **every** YAML file in the repository with the parser:

- `config/observability/`: `alertmanager/rules/runtime-alerts.yml`, `prometheus.yml`, `loki.yaml`, `tempo.yaml`, `otel-collector-config.yaml` and the two Grafana provisioning files → **all parse**.
- `k8s/`: all 8 files parse; `load_all` recovers **37 documents** and the separator count matches (`separators + 1 == documents` for every file) — no document is silently dropped.
- The alertmanager template and the inactive template both parse; the parsed tree exposes the routes, receivers, inhibit rules, group_by and the `|-` block-scalar message bodies correctly (the harness's own ALT-01 check reads them and passes).

### The construct that *would* be parsed incorrectly rather than rejected

`yamlmini` has one **silent-drop** defect: in `Scanner.parse_mapping`, when a key has an empty value and the next line is a **sequence at the same indentation**, the value is set to `None` and the loop breaks on `- ` (yamlmini.py:205-206, 216-222) — the whole sequence is discarded **without an error**.

`key:`
`- item one`
`- item two`

This is valid YAML (a sequence may be indented at the parent's level). `yamlmini` returns `{"key": None}` and **silently loses the sequence**.

**Assessment of the false-PASS risk**: I scanned every YAML file in `k8s/` and `config/observability/` for this pattern (a key with an empty value followed by a same-indent `- `) and found **zero occurrences today** — so no alert rule, no Alertmanager receiver and no Kubernetes manifest is currently affected. **However**, the failure mode is silent and the consequences are asymmetric:
- In the **Kubernetes validator**, a dropped sequence would mean a whole `spec` block (or a whole list of containers) is invisible → `pod_spec.get("containers")` returns `None` → the loop body never executes → the container is not checked for probes/resources/securityContext → **the corresponding FAIL conditions are never raised → a false PASS**. A manifest rewritten in that style (a very common Kubernetes formatting choice) would silently weaken K8S-03/04/05.
- In the **alerts validator**, a dropped `routes` list would produce `ci_routes == []` → the check *fails* loudly, and a dropped `receivers` list produces `telegram_receivers == []` → also a loud FAIL. The alerts validator is comparatively safe.
- Related blind spots of the same kind: **YAML anchors and merge keys** are not supported (`&anchor`/`*alias`/`<<:` are read as plain strings) — `docker-compose.yml` **cannot be parsed by this parser at all** (it fails with `Unexpected indentation at line 3`, i.e. it is *rejected*, which is safe, but it means the parser must never be pointed at the compose file).
- The parser also cannot express **multi-document files with a `---` inside a block scalar** (`load_all` splits on any line equal to `---`).

### Verdict

**PASS for the content in this repository** (nothing is mis-parsed today), with one **latent, silent, false-PASS-producing** defect (same-indent sequence) that the Kubernetes validator would be exposed to the moment a manifest is reformatted that way. The parser should reject the construct rather than silently drop it.

---

## 8. Gate staleness logic — `verification/gates.py`, `inventory/scanner.py`

### Correct

- **A missing fingerprint can never yield a PASS.** `evidence_is_fresh(entry, fingerprint) = bool(entry.get("source_fingerprint")) and entry.get("source_fingerprint") == fingerprint` (`gates.py:134-135`). Verified by simulation: an entry without a fingerprint ⇒ *fresh = False*; an entry with a different fingerprint ⇒ *False*; an entry with the current fingerprint ⇒ *True*.
- **A suite recorded before the fingerprint feature existed is treated as stale, not valid.** Every entry currently in `production-audit/state/verification.json` has **no** `source_fingerprint` (baseline, build, comments, concurrency, concurrency_inventory, e2e, edge, gateway, happy, integration, kafka, operations, payment, performance, security — all 15 suites show `fp: NONE`). Under the new logic every one of them resolves to **PENDING**, and GATE-4…GATE-18 plus GATE-12 (BUILD) therefore refuse to certify readiness. `completion_predicate()` consequently reports `production_ready: False`. **This is exactly the intended behaviour and it works.**
- The staleness check is applied consistently to all suite gates (`suite_status`, `gates.py:206-218`) and to the BUILD gate (`build_recorded and build_fresh`, `gates.py:242-245`).
- `source_fingerprint()` covers the material sources: 1483 files including `.github/workflows/ci.yml`, `docker-compose.yml`, `config/observability/prometheus.yml`, `config/observability/alertmanager/alertmanager.yml.template`, `k8s/microservices.yaml`, `load-tests/k6-scenario.js`, `scripts/notify-telegram.sh` and the whole `production-audit/harness/` tree — verified by direct membership test.
- GATE-22 was tightened correctly: it now requires **both** the manifest revision to equal HEAD **and** the manifest's fingerprint to equal the working-tree fingerprint.

### Weaknesses

- **W-1 (MEDIUM) — the fingerprint includes the git-ignored local `.env`, so it is machine-local and not reproducible.** `collect_files()` does not exclude `.env` (`build_inventory()` excludes it, `source_fingerprint()` does not). The same commit therefore produces **different fingerprints** on two machines, and editing a local `.env` invalidates every recorded suite. Freshness evidence cannot be shared or reproduced between a developer machine and CI.
- **W-2 (LOW) — a caller can override the computed fingerprint.** `record_suite_result()` builds `{"updated_at": …, "source_fingerprint": source_fingerprint(), **result}` — the spread comes **last**, so a `result` dict containing `source_fingerprint` silently wins. No current caller does this (`run_suite()` returns a document without that key), but the ordering makes the guarantee fragile.
- **W-3 (HIGH) — the new alerting and Kubernetes evidence is recorded but gates nothing.** `command_k8s` records the suite `"k8s"` and `command_alerts` records the suite `"alerts"`, but **no gate consumes either suite**: GATE-4…GATE-18 read happy, concurrency_inventory, payment, kafka, gateway, integration, security, build, edge, concurrency, e2e, performance, operations. A FAIL in the alerting validation (`alerts: FAIL` is already persisted) does **not** block the readiness predicate, and a PASS would not strengthen it either. The new evidence is decorative in the gate model.
- **W-4 (MEDIUM, pre-existing but load-bearing) — a suite that ran zero tests still yields PASS.** The `kafka` and `security` suites are persisted with `status: PASS`, `exit_code: 0` and **`tests_run: 0`** (their commands use `-Dtest=*Kafka*Test,…`; `interpret_test_exit()` finds no `Tests run:` line in the captured 4000-character tail, most likely because of `-T1C` parallel output). Neither the gate nor the freshness check looks at `tests_run`; a suite that executed no tests passes. This is a **false-PASS dimension that the staleness work did not address**, and the final report prints it verbatim ("kafka: PASS — 0 test").

---

## 9. Final report generator — `production-audit/harness/reporting/final_report.py`

### Correct

- The report is genuinely evidence-driven for the load, Stripe, Kubernetes, alerting, coverage, task and delivery sections: each value is read from persisted state (`_state()`, `_load_results()`, `load_tasks()`, `evaluate_gates()`), and the missing-evidence fallback is honest (`_metric(None)` → "không đo được" = "not measured").
- The final decision (section 37) is derived from `completion_predicate(evaluate_gates())` and cannot be claimed independently of the gates.
- Section 13 correctly refuses to print a PaymentIntent unless the persisted result is PASS **and** carries a `payment_intent_id`.

### Defects

- **R-1 (HIGH) — the report prints suite statuses without applying the staleness filter, so stale PASSes are presented as verified evidence.** `_suite_rows()` (`final_report.py:45-54`) reads `verification["suites"][*]["status"]` directly and never consults `evidence_is_fresh()` or the gate statuses. In the **currently committed report** (section 32) this prints: `baseline: PASS — 1913 test`, `build: PASSED — 2092 test`, `concurrency: PASS`, `happy: PASS`, `payment: PASS` … while **every one of those entries has no `source_fingerprint` and is therefore STALE** under the new gate logic. Section 36.1 ("ĐÃ XÁC MINH BẰNG BẰNG CHỨNG" / "VERIFIED BY EVIDENCE") repeats the same unfiltered claim ("Build reactor: PASSED"). The report therefore contradicts the gate evaluation it embeds: section 1 reports the gates as not passing while sections 32/36.1 present the same evidence as verified. **This is the clearest overclaim in the change set.**
- **R-2 (HIGH) — section 31 contains a hard-coded success statement that no evidence supports.** `final_report.py:349`:
  *"Cấu hình hạ tầng cảnh báo đã hiện thực đầy đủ: Prometheus đánh giá quyắt tắc, Alertmanager gom nhóm và chống lặp, Telegram nhận thông báo; có tuyến CI và runtime riêng biệt."*
  ("The alerting infrastructure is fully implemented: Prometheus evaluates the rules, Alertmanager groups and de-duplicates, Telegram receives the notifications; CI and runtime routes are separate.") This is a **static string**. It is printed regardless of whether `harness alerts validate` passed, and it is **false as written** on two counts: (a) the persisted alerting suite status is **FAIL**; (b) the CI and runtime paths are not separated by Alertmanager at all (item 1, A-3). Section 30 of the same report says the opposite ("Chưa có lần chạy kiểm thử giao hàng runtime") — the report contradicts itself.
- **R-3 (HIGH) — sections 13 and 36.2 contain hard-coded *failure* narratives.** `final_report.py:226` prints a fixed sentence asserting that "the real Stripe API returned HTTP 401 Invalid API Key for the sk_test_ key configured in .env, and SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=whsec_local_e2e_only is a local signing secret and not a real Stripe signing secret", and `final_report.py:419` repeats the equivalent claim **unconditionally**, whatever the persisted result says. Today this happens to agree with the persisted evidence (`stripe-results.json`: `api_authentication: FAIL`, `webhook_secret: FAIL`), but it is a hard-coded claim about a live environment, it is emitted even when the persisted status is PASS (it would then read "PASS — the key was rejected by Stripe"), and it **embeds the literal name and value of a local signing secret** into a committed report artifact. The report's own preamble promises "every statement is generated automatically from stored evidence" — these three lines break that promise.
- **R-4 (MEDIUM) — the load evidence in the report is unusable.** `_load_results()` reads the raw k6 `summary.json` (`data.metrics`), while the field lookups use the **summarised** names (`requests`, `request_rate_per_second`, `iterations`). The committed section 20 therefore prints "yêu cầu không đo được, không đo được" next to a raw Python dict: `lượt lặp {'type': 'counter', 'contains': 'default', 'values': {'count': 5557, 'rate': 32.67…}}`. The measurements exist but cannot be read; section 21 reports every latency percentile as "not measured" although the data is present. (This *under*-claims rather than over-claims, but it means the load section carries no usable evidence.)
- **R-5 (MEDIUM) — sections 8/9 silently omit the invariant results.** The invariant lines are printed only `if load_invariants:`, and `production-audit/state/performance-baselines.json` does not exist → the report prints **nothing** about the eight business invariants (neither a result nor a "not verified" marker), while section 19 asserts the profiles are in place.
- **R-6 (LOW) — the CLI action `stripe sandbox-smoke` runs `verify_configuration()`** (identical to `verify-config`); the name promises a sandbox smoke test that never happens.

---

## 10. CI workflow — `.github/workflows/ci.yml`

### Correct

- The workflow is **valid YAML** and the job graph is acyclic. `notify-telegram` `needs:` all six other jobs; no job depends on itself, so a failure **inside** the notification job cannot skip it.
- Every command it runs exists in the repository:
  - `./mvnw -T1C validate` / `./mvnw -T1C test` → `mvnw` exists
  - `python3 production-audit/bin/harness comments scan` → the `comments scan` sub-command exists (`cli/main.py:754-755`)
  - `python3 -m unittest discover -s production-audit/harness/tests -t production-audit` → the `harness` package is importable from `production-audit`
  - `python3 production-audit/bin/harness test --suite concurrency` / `--suite integration` → the `test --suite` sub-command exists
  - `docker build -f gateway-service/Dockerfile` / `-f order-service/Dockerfile` → **both Dockerfiles exist**
  - `python3 production-audit/bin/harness k8s validate` / `alerts validate` → both sub-commands exist
  - `sh scripts/notify-telegram.sh` → the file exists
- **No job exposes secrets.** `permissions: contents: read` at workflow level; the secrets are passed only as `env:` to the notification step and are never echoed, never passed on a command line, and never printed by the script. The token appears only inside the request URL, and `curl --fail-with-body` output is discarded on failure, so it does not reach the log.
- The CI validates the Alertmanager configuration with a **fake** token substituted by `sed` — no real credential is written into the CI workspace.

### Defects — the pipeline is red on arrival

- **C-1 (HIGH) — `kubernetes-validation` fails deterministically.** `harness k8s validate` returns **FAIL** on the current manifests (reproduced by executing `validate_manifests()`): `K8S-02` (required kind `ServiceAccount` is missing — no ServiceAccount exists in `k8s/`), `K8S-03` (13 deployments without a `startupProbe`), `K8S-05` (13 containers without a `securityContext`), `K8S-08` (6 multi-replica deployments without a PodDisruptionBudget). The job exits `EXIT_GATE_FAILED` ⇒ **the job fails on every push and pull request**, and the persisted state already shows `k8s: FAIL`.
- **C-2 (HIGH) — `alerting-validation` fails deterministically.** `harness alerts validate` returns **BLOCKED** when the Telegram credentials are absent (reproduced: ALT-07 = BLOCKED). The CI job does **not** provide the secrets to that job, so ALT-07 can never be satisfied there, and `command_alerts` maps BLOCKED to `EXIT_BLOCKED` = **2** ⇒ **the job fails on every run**. The workflow validates the *configuration* but the command also demands *delivery credentials*, conflating "is the configuration valid" with "can we deliver".
- **C-3 (HIGH) — `validate-and-test` fails deterministically.** The new `harness/tests/test_stripe.py` contains `test_the_provider_defaults_to_stripe`, which asserts `credentials["sandbox"]` is true. The sandbox flag is defined in neither `.env`, nor `.env.example`, nor the CI environment ⇒ the test fails. **Reproduced**: `FAILED (failures=1)`.
- **C-4 (HIGH, consequence) — the notification job fires on every single run.** Because three jobs fail unconditionally, `if: ${{ failure() }}` is true on every push ⇒ a Telegram "CI FAILURE" message is emitted for every commit, even when nothing is actually broken. The alert channel becomes noise and will be ignored — the opposite of the intent of the change set.
- **C-5 (MEDIUM) — the notification script's token guard is not a format check.** `case "${token}" in [0-9]*:[A-Za-z0-9_-]*)` (`notify-telegram.sh:12-19`) is a **shell glob**, not the token format: it accepts any string that starts with a digit, contains a colon and contains only word characters afterwards — e.g. `1:x` passes. The strict pattern (`^[0-9]{6,12}:[A-Za-z0-9_-]{20,}$`) exists only in the compose service and in Python. The requirement "the token format guard rejects a malformed token before it is rendered" is therefore **not satisfied on the CI path**: a malformed token is sent to the Telegram API instead of being rejected locally.
- **C-6 (MEDIUM) — `failure()` does not cover `cancelled()`.** The notification job runs only on `failure()`; a cancelled or timed-out run produces no notification.
- **C-7 (MEDIUM) — the notification job's condition depends on all six `needs`, and a skipped dependency can suppress it.** A job that is skipped (for example by a future conditional) can leave the notification job skipped even though another needed job failed. The documented robust form is `if: ${{ always() && contains(needs.*.result, 'failure') }}`.
- **C-8 (LOW) — the workflow does not validate the compose file or the inactive Alertmanager template.** `docker-compose.yml` was modified in this change set (it uses YAML anchors/merge keys) and `alertmanager.inactive.yml.template` is new, but neither is validated anywhere in CI.

---

## FALSE-PASS risks (ranked)

| # | Severity | Risk | Where | Evidence |
|---|----------|------|-------|----------|
| FP-1 | **CRITICAL** | 13 of 23 runtime alert rules can never fire (metrics do not exist in the platform); the alerting validator reports PASS anyway | item 2 | `runtime-alerts.yml` vs. the whole source tree (`OrderMetrics.java:24`, `InventoryMetrics.java:25`); no `Counter.builder` anywhere |
| FP-2 | **HIGH** | The final report presents **stale** suite results as verified evidence (sections 32 / 36.1) while the gates mark them PENDING | item 9 | committed report lines 184-201 vs. `verification.json` (`fp: NONE` for all 15 suites) |
| FP-3 | **HIGH** | The final report prints a hard-coded "alerting infrastructure fully implemented" success statement regardless of the evidence, and contradicts its own section 30 | item 9 | `final_report.py:349` vs. `alerts: FAIL` in the state file |
| FP-4 | **HIGH** | The invariant checks can pass **vacuously**: INV-08 uses the non-existent status `RESERVED` (always 0 ⇒ always PASS); INV-05/INV-06 are enforced by unique indexes; every COUNT-based invariant passes on empty tables | item 5 | `UsageStatus.java:3`; `uk_payment_idempotency_key`; `uk_cur_order_id` |
| FP-5 | **HIGH** | The load runner reports `PASS` when the measurement host was contaminated, and returns `EXIT_OK` when the invariants are **BLOCKED** (not evaluable) | item 5 | `runner.py:194-212`; the load branch of `cli/main.py` |
| FP-6 | **HIGH** | A suite that executed **zero tests** (`kafka`, `security`) is recorded as PASS and satisfies a gate; the freshness check does not look at `tests_run` | item 8 | `verification.json`: `kafka | PASS | exit 0 | tests_run 0` |
| FP-7 | **HIGH** | The **rendered** Alertmanager config containing the live token is written to a path that is **not git-ignored** | item 3 | `alerts/render.py:34-42`; no `.gitignore` rule |
| FP-8 | **MEDIUM** | The harness' credential scan (ALT-06) **cannot see any file of this change set** (all untracked) and its patterns miss `sk_test_*` keys and tokens not prefixed by `TELEGRAM_BOT_TOKEN` | item 3 | `git ls-files` returns nothing for the new paths; `validation.py:17-18` |
| FP-9 | **MEDIUM** | `yamlmini` silently drops a sequence that is indented at the same level as its parent key; in the Kubernetes validator that would hide containers/spec blocks and suppress the probe/resource/securityContext failures | item 7 | `yamlmini.py:205-222`; not present in today's manifests |
| FP-10 | **MEDIUM** | The CI pipeline fails on every run (3 deterministic failures), so the notification fires constantly and the "green = healthy" signal is destroyed | item 10 | reproduced `k8s validate` FAIL, `alerts validate` BLOCKED (exit 2), `test_the_provider_defaults_to_stripe` FAIL |
| FP-11 | **MEDIUM** | The new alerting and Kubernetes evidence is recorded but **gates nothing** — no gate consumes the `alerts`/`k8s` suites | item 8 | `gates.py:206-226` vs. `cli/main.py` |
| FP-12 | **MEDIUM** | `test_webhooks()` returns PASS with a **placeholder** webhook secret and verifies the harness against itself (circular), so a PASS says nothing about the platform's real webhook handling | item 6 | `sandbox.py:281-302` |
| FP-13 | **MEDIUM** | A fabricated `sk_test_…` placeholder is classified as a genuine test key and reported as `secret_key_mode: PASS` | item 6 | `sandbox.py:93-100` |
| FP-14 | **LOW** | The fingerprint includes the local `.env`, so freshness evidence is machine-local and not reproducible between a workstation and CI | item 8 | `source_fingerprint` covers `.env` |
| FP-15 | **LOW** | The CI notification script's "token format guard" is a weak glob and does not reject a malformed token before it is used | item 10 | `notify-telegram.sh:12-19` |

## FALSE-FAIL risks (ranked)

| # | Severity | Risk | Where | Evidence |
|---|----------|------|-------|----------|
| FF-1 | **HIGH** | INV-07 reports a legitimate **partially refunded** order as a payment/order divergence, because the payment row's status becomes `PARTIALLY_REFUNDED` and the counterpart query only accepts `CAPTURED`/`REFUNDED` | item 5 | `WebhookEventService.applyPartialRefund`; `PaymentStateMachine`; `invariants.py:124-126` |
| FF-2 | **HIGH** | The CI pipeline is red on arrival: `kubernetes-validation` (4 failing manifest checks), `alerting-validation` (BLOCKED ⇒ exit 2) and `validate-and-test` (the new Stripe test) all fail deterministically | item 10 | reproduced locally; `production-audit/state/verification.json` (`k8s: FAIL`) |
| FF-3 | **HIGH** | The `Service` field of the runtime incident contract is empty for every `up`-based alert, and all `ServiceDown` alerts collapse into one group | item 1 | `up` carries only `{job, instance}` |
| FF-4 | **MEDIUM** | The inhibition rule "ServiceDown suppresses warnings for the same service" can never match (empty `application` on the source) | item 1 | item 1, A-2 |
| FF-5 | **MEDIUM** | `HighHttp5xxRate` fires a **critical** page on a single 5xx in a low-traffic service (no minimum-traffic guard); `SearchDependencyFailure`/`StorageDependencyFailure` page on any single 5xx and duplicate the 5xx rule | item 2 | item 2, R-1/R-2 |
| FF-6 | **MEDIUM** | `HighMemoryPressure` is evaluated per JVM memory pool (Micrometer's `id` label), so an Eden-space ratio pages even when the heap is healthy | item 2 | Micrometer 1.10+ `jvm_memory_*_bytes{area,id}` |
| FF-7 | **MEDIUM** | A malformed chat id containing `&` makes the awk render silently substitute the placeholder back into a **valid** config ⇒ Alertmanager starts and every notification fails with "chat not found" (no startup signal) | item 4 | item 4, D-1 |
| FF-8 | **MEDIUM** | A partially configured environment (token set, chat id empty) produces a valid-but-dead Alertmanager in the compose path (the Python path fails closed) | item 4 | item 4, D-2 |
| FF-9 | **MEDIUM** | The Stripe harness reports a successful end-to-end payment whenever the confirm call returns HTTP 200, even if the PaymentIntent is `requires_action`/`requires_payment_method` | item 6 | `sandbox.py:233-235` |
| FF-10 | **MEDIUM** | The final report contradicts itself: section 31 states the alerting infrastructure is fully implemented while section 30 states no runtime delivery run exists | item 9 | `final_report.py:349` vs. `:336-344` |
| FF-11 | **LOW** | The CI notification job can be suppressed when a needed job is skipped (`failure()` only) and never fires for cancelled runs | item 10 | item 10, C-6/C-7 |
| FF-12 | **LOW** | The load report section prints raw k6 metric dictionaries and reports every percentile as "not measured" although the data is present; the invariant sections print nothing at all when no run is persisted | item 9 | item 9, R-4/R-5 |

---

## Required follow-up before this change set can be certified

1. Replace the 13 non-existent metric names in `runtime-alerts.yml` with the metrics the platform actually publishes, or register the missing counters; and make the alerts validator **check that each rule's metric exists** (otherwise ALT-02 is a false pass).
2. Give the `up`-based alerts an `application` label (e.g. via a `label_replace`/join on `instance`) so the incident contract's "Service" field and the ServiceDown inhibition both work; or render `{{ $labels.instance }}` in the "Service" line of the runtime templates.
3. Fix INV-08 (`RESERVED` → `PENDING`) and INV-07 (accept `PARTIALLY_REFUNDED`, or use `previous_status`); make the invariant set non-vacuous by asserting that the tables/orders were actually exercised.
4. Make the load runner **fail** (not PASS) when the measurement host is contaminated or the invariants are BLOCKED.
5. Reject the same-indent sequence in `yamlmini` instead of silently dropping it.
6. Apply the freshness filter in the final report (sections 32/36.1) and replace the hard-coded strings in sections 13/31/36.2 with values derived from the persisted evidence.
7. Fix the CI pipeline: make `k8s validate` and `alerts validate` reflect the intended policy (or align the manifests with the validator), make the alerting validation credential-independent (split the credential check out of `alerts validate`), fix `test_the_provider_defaults_to_stripe` (and add the sandbox flag to `.env.example`), harden the token guard in `notify-telegram.sh`, and use `always()` + an explicit outcome test for the notification job.
8. Add `config/observability/alertmanager/alertmanager.yml` to `.gitignore`; extend the credential scan to `sk_test_*` keys, to tokens not preceded by the variable name, and to `.json`/`.md`/`.conf` files; scan the working tree, not only tracked files.

---

## Final verdict

**`QA_FAIL`**

Rationale: the change set is not certifiable. Three CI jobs fail deterministically on the current repository (reproduced, not inferred), the alerting rule file contains 13 rules whose metrics do not exist anywhere in the platform while the validator reports PASS, one business invariant is permanently vacuous and another permanently false-failing, the final report prints hard-coded success statements and stale PASSes as verified evidence, and the credential-rendering path can emit a live token into a non-ignored file. The gate-staleness work (item 8) is the strongest part of the change set and behaves exactly as specified — including correctly refusing the pre-fingerprint evidence — but it does not compensate for the defects above.

Items **not** fully verifiable in this environment (no Docker daemon, no live Prometheus/Alertmanager/Telegram/Stripe, no Maven build permitted): the actual `promtool`/`amtool` acceptance of the rule file and the Alertmanager templates, the real behaviour of the Alertmanager route tree and inhibition under load, the runtime `application` label on the actuator metrics (which depends on whether `utils/common-spring/src/main/resources/application.yml` is in effect for each service — only one `application.yml` is loaded from the classpath root and the per-service files shadow the shared one), the end-to-end behaviour of the k6 scenarios against a live stack, and the real Stripe sandbox path. These are recorded as evidence gaps, not as passes.
