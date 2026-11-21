# Kubernetes Manifest Review — run-20261122-f242158 (manifests only; NO cluster test possible)

Facts (independently read from k8s/):
- 13 service Deployments in k8s/microservices.yaml (all replicas: 2), each with startupProbe + livenessProbe + readinessProbe and resources.requests/limits. Gateway in k8s/gateway-service.yaml.
- PDBs (k8s/workload-protection.yaml): order, payment, product, inventory, shipping, auth (maxUnavailable: 1) + ServiceAccount shop-system.
- HPA: ONLY gateway-service (autoscaling/v2, min 2 / max 10, CPU 75%). NO HPA for any app service. NO KEDA ScaledObject anywhere in k8s/.
- Ingress: TLS configured (shop-tls-cert) in k8s/ingress.yaml.
- NetworkPolicy: NONE anywhere (matches arch-design ARCH-9) — in-cluster callers bypass gateway edge controls entirely.
- k8s/secrets.template.yaml: placeholder values (changeme...) — template-only by design, but nosealed-secrets/external-secrets mechanism present; secrets management relies on manual ops.
- debezium-cdc.yaml exists but kafka-messaging audit found NO Debezium path in the code (event flow is outbox-relay based) — cdc manifest is dead config.
- prometheus-alerts.yaml provides cluster-level alert rules; compose runtime uses config/observability + runtime-alerts.yml (verified separately).

Cross-checks:
- gateway trusted-proxy hops=0/empty CIDRs with nginx ingress in front (ARCH-1, P1): per-client rate limiting collapses to the ingress IP; admin IP allowlist inactive (GatewaySecurityPostureValidator logs a WARN only). Runtime confirmation: gateway startup log warns "Admin IP allowlist is INACTIVE".
- deployment manifests do not set GATEWAY_TRUSTED_PROXY_CIDRS/HOPS nor ADMIN_IP_ALLOWLIST (checked env blocks) — the k8s deployment would exhibit the same collapse as ARCH-1 describes.

Runtime verification status:
- kubectl v1.36.2 present but NO cluster context configured → no deploy/scale/pod-restart tests possible.
- Classification: K8s runtime behavior = INSUFFICIENT EVIDENCE; scaling = gateway-only HPA, app tier fixed at 2 replicas with no autoscaling — Black Friday capacity at the app tier depends entirely on vertical headroom (limits unreviewed against real load).
