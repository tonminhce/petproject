# Kubernetes Manifest Review — run-20261122-f242158 (manifests only; NO cluster test possible)

Facts (independently read from k8s/):
- 13 service Deployments in k8s/microservices.yaml (all replicas: 2), each with startupProbe + livenessProbe + readinessProbe and resources.requests/limits. Gateway separately in k8s/gateway-service.yaml.
- PDBs (k8s/workload-protection.yaml): order, payment, product, inventory, shipping, auth (maxUnavailable: 1) + ServiceAccount shop-system.
- HPA: ONLY gateway-service (autoscaling/v2, min 2 / max 10, CPU utilization 75%). NO HPA for any app service. NO KEDA ScaledObject anywhere in k8s/.
- Ingress: TLS configured (secretName shop-tls-cert) in k8s/ingress.yaml.
- NetworkPolicy: NONE anywhere (confirms arch-design ARCH-9) — in-cluster callers bypass every gateway edge control.
- k8s/secrets.template.yaml: placeholder values (changeme_in_production, sk_test_mock...) — template by design; no sealed-secrets/external-secrets mechanism present; secret management is manual ops.
- debezium-cdc.yaml exists but kafka-messaging audit found NO Debezium path in code (event flow is outbox-relay based) — dead manifest.
- prometheus-alerts.yaml provides cluster-level rules; compose runtime uses config/observability + runtime-alerts.yml (verified separately).

Cross-checks:
- gateway trusted-proxy hops=0/empty CIDRs with nginx ingress in front (ARCH-1 P1): per-client rate limiting collapses to ingress IP; admin IP allowlist inactive (runtime WARN observed in gateway startup log: "Admin IP allowlist is INACTIVE").
- deployment manifests do not set GATEWAY_TRUSTED_PROXY_CIDRS/HOPS nor ADMIN_IP_ALLOWLIST env — k8s deployment exhibits exactly the ARCH-1 posture.

Runtime verification status:
- kubectl v1.36.2 present, NO cluster context → no deploy/scale/pod-restart/rolling-update tests possible.
- Classification: K8s RUNTIME BEHAVIOR = INSUFFICIENT EVIDENCE. App tier fixed at 2 replicas with no autoscaling; Black Friday scaling at the app tier is impossible without manifest changes (ORCH-6, P2).
