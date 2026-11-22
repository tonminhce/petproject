# Observability & Alerting Evidence — run-20261122-f242158 (live verification 19:14-19:22 UTC)

## Metrics (Prometheus :9095)
- Targets: 17 active; 15 up. DOWN: postgresql + redis jobs — they scrape OPTIONAL postgres-exporter/redis-exporter (observability profile, not running per AGENTS.md rule 3).
- Business/technical series present: http_server_requests_seconds_count (140 series), resilience4j_circuitbreaker_state (60 series), per-service outbox gauges/relay timers (order_outbox_pending_count, inventory_outbox_pending_count, product_outbox_pending_count, *_outbox_relay_duration_seconds_*), payment_webhook_failed_permanent_total, notification_failed_permanent_total, order_commit_stuck.
- Live metric correctly reflected incidents: order_commit_stuck=18 during breaker outage (0 after); resilience4j_circuitbreaker_state{state="open"}=1 for 5 order-service breakers during the incident.
- OTLP: services push metrics to otel-collector:4318 (log evidence in container startup: "Publishing metrics for OtlpMeterRegistry every 1m").
- GAPS (source-confirmed): rating service has ZERO backlog metrics (KFK-3); no kafka_consumer_lag metrics in Prometheus (no lag exporter — KEDA-scale-lag claims impossible).

## Logs
- Structured JSON audit logs with traceId (payment-service AUDIT lines observed live).
- Log correlation pattern configured fleet-wide ([%X{traceId:-},%X{spanId:-}] in application-shared.yml:58).
- Loki :3100 /ready 200 after stabilization (earlier 503 during churn); alloy shipping logs to loki (observed query traffic in loki logs).

## Traces (Tempo :3200)
- Live trace search returned real spans across services: http server spans, scheduler task spans (orderReconciliationScheduler.reconcileStuckOrders, shipmentCreationRetryScheduler.retryPendingCreations, inventoryOutboxRelay.recoverStrandedClaims).
- Gap: my earlier HTTP-capture traceId dde613d5… not retrievable by direct GET (Tempo ingester churn during operator restarts); search API confirms ingestion works. Trace propagation through Kafka producers/consumers: correlation ids present in consumer spans (task spans carry no parent HTTP - by design, new trace per poll batch).

## Alerts (Alertmanager :9093)
- Prometheus rules: 2 groups / 18 rules; DURING INCIDENT 5 fired simultaneously: ServiceRestartLoop (warning), CircuitBreakerOpen (critical), OrderCommitStuck (critical), DatabaseUnavailable (critical), RedisUnavailable (critical).
- FALSE POSITIVES: DatabaseUnavailable/RedisUnavailable key on up{job="postgresql"}/up{job="redis"} = the OPTIONAL exporters being absent, not the actual databases (both healthy). Rule design defect.
- CRITICAL GAP: only receiver = `telegram-disabled`. Cause: container was created BEFORE TELEGRAM_BOT_TOKEN/CHAT_ID were set in .env; compose renders alertmanager config at CONTAINER CREATION (entrypoint sed), so a running container never picks up new env. .env HAS both values.
- Bot token validated LIVE non-intrusively: https://api.telegram.org/bot$TOKEN/getMe → ok:true bot @ecommerceminhBot. Chat id present.
- Conclusion: alert pipeline (rules→AM) FUNCTIONAL; NOTIFICATION DELIVERY NOT VERIFIED (delivery path exists in config template with routing/severity split, but no real send observed this session). Recreating the alertmanager container should activate it; left un-executed to avoid mutating the operator's running stack mid-audit (bounded-risk decision, disclosed).
- CI Telegram: .github/workflows/ci.yml has ALERT_ENVIRONMENT=ci env + notify-telegram.sh script exists (scripts/notify-telegram.sh) — CI delivery not observed (no workflow run access in this session) → NOT VERIFIED for CI path.

## Grafana
- 2 provisioned dashboards visible via API (Petproject, Fleet Overview) with admin login.

## POST-RESTORE UPDATE (20:32-20:36 UTC)
- Alertmanager recreated with current .env: receivers switched to telegram-runtime-critical/warning (was telegram-disabled); rendered config verified in-container (real bot_token + chat_id -5312***, group_wait 60s warning / 30s? critical per template).
- Smoke test: 1 synthetic warning alert POSTed to AM API (HTTP 200), left active through group_wait window with ZERO notify-error logs (AM logs notify failures at error level; successful sends are silent). Bot outgoing messages are NOT visible via getUpdates (protocol limitation) — chat-side visual confirmation remains for the operator. Classification: DELIVERY CONFIGURATION VERIFIED + NO-ERROR OBSERVED; absolute chat-side proof not captured.
- After full observability restart: DatabaseUnavailable/RedisUnavailable false alerts CLEARED (postgres/redis exporters now running with the profile) — the false-positive alert source is operational again; rule design (keying on optional exporters) remains a P2 for the next time the profile is off.
- Prometheus re-initialized: firing alerts = 0 (breakers closed after service restarts; order_commit_stuck=0).
