# Security Assessment — run-20261122-f242158 (independent source + live probes)

## Trust boundaries (verified)
- JWT: issuer-pinned Keycloak realm on gateway AND services; lazy decoders (LazyJwtDecoder). No X-User-* header trust — gateway forwards only the original Authorization; services re-validate tokens themselves (architecture/actual-system-map.md). `azp` (client) acts as the audience gate; `aud` never validated fleet-wide (ARCH-16 P3, deliberate but undocumented).
- Service-to-service: ServiceCallerGuard checks token azp against allowed client ids (payment/promotion/inventory reservation endpoints); human ADMIN/MANAGER role BYPASSES the client check (ServiceCallerGuard.java:24-26) — deliberate admin-ops design; means stolen admin JWT can create/capture/refund payments directly (accepted posture, noted).
- Roles: USER vs ADMIN/MANAGER realm roles; @PreAuthorize on money endpoints; ownership checks on cart/order/addresses (user-scoped /me paths; BackofficeOrderReturnController guarded). Live probe: USER token on POST /payments → 403 ERR-0403 (correct).
- Webhooks: Stripe HMAC signature verified with real crypto incl. timestamp window (WebhookSignatureVerifier.java; live: bad signature → 401 PAY-5005, valid → 200, replay → dedupe). Custom PSP verifiers (MoMo/VNPay): no freshness window (ORD-19 P2).
- Gateway edge: rate limit Redis-Lua atomic, FAIL_CLOSED on outage (live-observed ERR-0503 when redis link flapped); circuit breakers + bulkhead at edge; CORS origin allow-list single entry; actuator exposure minimal (health/info/prometheus — ARCH-20 P3: /v3/api-docs publicly exposed → internal API surface disclosure).

## Confirmed defects (not "everything critical")
- Missing controls (P1/P2): no brute-force lockout + ROPC on public client (AUTH-2 — OAuth 2.1 removes password grant; official: oauth.net/2/grant-types/password/); no NetworkPolicy (ARCH-9) so any pod can call service ports directly bypassing gateway edge controls; admin IP allowlist inactive in prod posture (ARCH-1, runtime WARN observed); changeme default secrets in service configs (AUTH-3) — DeploymentCredentialContractTest exists, defaults still shipped.
- Data handling (P2): all order emails fall back to one mailbox (KFK-6 — customer notification privacy + useless for prod); emailVerified=true on registration (AUTH-4).
- Injection/surface: no raw SQL concatenation found in reviewed repositories (JPA @Query parameterized); path traversal not applicable (S3 keys server-generated); 2MB codec cap; graceful error mapping without stack traces (include-stacktrace: never).
- Secrets in repo: .env git-ignored (checked .gitignore); docker-compose references env only; k8s uses secret refs (template placeholders). No live keys found; Stripe keys present ONLY in untracked .env as sk_test_* (verified test-mode).

## Container/k8s posture
- Dockerfiles: non-root USER appuser (spot-checked product-service/Dockerfile). Compose ports: observability bound to 127.0.0.1 only (verified docker port map); business services on network only, gateway sole ingress (8080).
- K8s: securityContext/PDB present; secrets manual template; no RBAC beyond ServiceAccount shop-system (no explicit RoleBindings tightening default — NEEDS cluster verification).

## Overall
AuthN/AuthZ core is solid and correctly implemented at every layer verified. The realistic exposure comes from OPS gaps (lockout, NetworkPolicy, trusted-proxy, default secrets) rather than code-level authz holes. No confirmed exploitable path from internet in the audited stack; in-cluster bypass is the strongest finding.
