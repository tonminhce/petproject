# Observability & Incident Readiness — run-20261122-f242158
(detail: evidence/obs/observability-run-log.md)

## What WORKS (live-verified)
- Metrics: full Prometheus surface — HTTP histograms, JVM, circuit-breaker states, per-service outbox backlog gauges + relay timers, payment_webhook_failed_permanent_total, notification_failed_permanent_total, order_commit_stuck. OTLP export via otel-collector. Grafana provisioned (2 dashboards).
- Live detection proof: during the breaker incident, 5 alerts fired correctly (CircuitBreakerOpen, OrderCommitStuck=18, ServiceRestartLoop…) — the metric layer DETECTED the outage within minutes.
- Logs: structured JSON audit lines with traceId; Loki ingesting (ready 200); correlation pattern fleet-wide.
- Traces: Tempo search returns real cross-service spans (HTTP + scheduled task spans); trace correlation in logs.
- Recovery machinery visible in traces/metrics: reservation sweeper, order reconciliation (stuck 18→0), shipment retry, webhook retry, outbox relay recovery.

## What's BROKEN (confirmed)
- ORCH-3 P1: alertmanager ran with `telegram-disabled` as ONLY receiver while 5 critical alerts were firing — zero notifications. Root cause: compose renders alertmanager config at container CREATION; the container predates TELEGRAM_BOT_TOKEN in .env (token itself validated live: bot @ecommerceminhBot ok:true). One recreate fixes it; still, deployed state = silent criticals.
- ORCH-7 P2: DatabaseUnavailable/RedisUnavailable alerts keyed on OPTIONAL exporter jobs (not running) = permanent false criticals masking real signal (alert fatigue).
- KFK-3 P2: rating outbox backlog invisible (no metric); no kafka consumer-lag metrics at all → no lag alerting possible today (and partitions=1 makes lag scaling moot).
- KFK-4 P2: DLT is a dead end (no replay endpoint enabled, no metrics).
- CI telegram path (ALERT_ENVIRONMENT=ci + notify-telegram.sh): present but NOT observed delivering in this session → NOT VERIFIED.

## Incident readiness verdict
Detection: GOOD (metrics+reconciliation). Diagnosis: GOOD (traces+structured logs). NOTIFICATION: BROKEN in deployed state (P1). Self-healing: partial (orders/reservations recover; breakers need restart-free half-open recovery but ORCH-1 makes them flap; search/ES drift unalerted).
