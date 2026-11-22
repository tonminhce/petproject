# Kubernetes & Performance Assessment — run-20261122-f242158

## Kubernetes (manifests reviewed; runtime = INSUFFICIENT EVIDENCE)
- No cluster context on audit machine → no deploy/scale/rolling-update/pod-kill tests possible. Claim class per mission §18: manifests-only.
- Good: startup/liveness/readiness probes on all 13 services + gateway; requests/limits set; PDBs maxUnavailable=1 for 6 services; ingress TLS configured; non-root containers; graceful shutdown in shared config.
- Gaps (confirmed from manifests + runtime WARN): HPA ONLY on gateway — app tier fixed 2 replicas, NO KEDA despite Kafka consumers (no lag-based scaling path); trusted-proxy hops=0 behind nginx ingress → per-client rate limiting collapses to ingress IP + admin allowlist inactive (ARCH-1 P1); zero NetworkPolicy (ARCH-9 P2); debezium-cdc.yaml is dead config (no Debezium in code); secrets management manual template.
- Kafka partitions: topics auto-created 1 partition RF=1 (compose + no provisioning) → consumer parallelism ceiling = 1 regardless of replicas; KEDA/HPA scale-out of consumers would be useless until partitions are provisioned (KFK-9 P2 — capacity-relevant).

## Performance / Black Friday (measured on single-node compose; production topology absent)
- Bounded races (live): 200-way coupon storm → exact quota, no breach; 30-user stock-5 race → exact 5, no oversell; 20-way same-cart → 1 order (+ghost residue).
- Throughput: mixed public reads 194 rps, p50 36ms / p95 205ms / p99 388ms, zero 5xx in stable window; p95 9.7s on rejected reserve storm (retry backoff on quota exhaustion — acceptable but visible to clients).
- Resource snapshot during ramp: gateway 120-139% CPU (2-core allocation), order-service 90%; postgres/kafka modest; no OOMs; 16GB host was SHARED with external k6 operator — long-soak numbers would be unreliable, disclosed.
- KILL-SWITCH FINDING: ORCH-1 P0 — a single sold-out hot SKU trips order-service circuit breakers (business 409s counted as failures) and takes down ALL checkouts platform-wide; live-reproduced at 19:22 UTC. This converts the #1 Black Friday event (flash-sale sellout) into a total checkout outage. Until fixed, Black Friday readiness is NOT achievable regardless of capacity.
- Verdict on capacity: INSUFFICIENT EVIDENCE for target workload (no approved production-scale environment, no multi-instance runtime, partitions=1 ceiling). Verdict on spike SAFETY: NO-GO independent of capacity due to ORCH-1.
