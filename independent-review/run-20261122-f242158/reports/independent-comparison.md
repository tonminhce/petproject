# Independent Comparison vs Existing Production Harness — run-20261122-f242158
(per mission §27 Part 16: comparison made ONLY after independent assessment was established; harness artifacts treated as untrusted claims)

## What the harness's own committed state says (production-audit/runs/run-20261118-a2b6e6fc)
- verification.json completion predicate (evaluated 2026-11-21T19:00:08Z): **failing_gates includes GATE-4,5,6,7,8,9,10,12,13,14,15,16,17,(18),22**; GATE-19 = **FAIL** ("1 of 1 independent reviews report a failing verdict"); GATE-22 PENDING "evidence does not match the current revision and working tree"; GATE-12 "reactor build evidence is stale". Only GATE-1 (coverage), GATE-2, GATE-3, GATE-11 (no comments), GATE-20 (branch), GATE-21, GATE-25 record PASS.
- The harness's own final-candidate-review (independent-reviews/final-candidate-review.md) verdict: **FAIL** with 7 defects — N1 "false pass inside the candidate" (production_ready:true claim vs computed false), N2 Stripe acceptance evidence NOT COMMITTED, N3 GATE-22 unfixable as structured, N4 concurrency race test deleted as a "repair", N5 load sweep non-supporting (flash-sale FAIL 791 failures; spike ratio 0.0232; promotion-storm BLOCKED 681 expired credentials), N6 contradictory capacity records, N7 deleted review artifacts.
- GATE-25 claims autoscaling observed on a real cluster — NOT independently reproducible in this session (no kube context on this machine); treated as their historical claim, not evidence.

## Agreements (independently re-derived by this audit)
1. **Release-blocking status**: both conclude the candidate must NOT ship. Their GATE-19 FAIL matches my NO-GO (3 confirmed P0/P0-class items).
2. **Money paths structurally improved but boundary-unsafe**: their F1 (provider refund double-count) fix verified by their reviewer; my audit adds ORD-1/ORD-3/ORD-5/ORD-13 — cancel-vs-capture and capture-uniqueness remain open, which THEIR gates never enumerated as defects.
3. **Load sweep failures ≈ my ORCH-1 root cause**: their flash-sale FAIL (791 checkout failures) and spike ratio 0.0232 are the exact signature of the confirmed circuit-breaker kill-switch (business 409 sellouts → breaker OPEN → mass checkout failures). Their evidence recorded the SYMPTOM; my audit isolated the MECHANISM (no ignoreExceptions; InventoryServiceClient.java:39) and reproduced it live. This is the single most valuable convergence.
4. **Stripe acceptance was real**: their GATE-6 text claims genuine api.stripe.com acceptance; my independent run CONFIRMS the integration itself works against real Stripe test mode (capture/refund/dedupe/error-mapping). But my audit also establishes what theirs did not: the WEBHOOK DELIVERY leg is structurally unverifiable in this environment (example.com endpoint; only synthetic evt_acceptance_* events) — so "every case was proven" (GATE-6 text) OVERSTATES the webhook leg.
5. **No-comment policy**: GATE-11 PASS — independently re-verified by full-tree scan (676 main + 430 test files, zero comments). Confirmed.

## Disagreements / overstatements in harness claims
- **GATE-21 PASS "no unresolved release blocker"** is UNSUPPORTED: my audit confirms ORD-1 (money kept after cancel+capture) and ORCH-1 (checkout kill-switch) — both release blockers, neither tracked in their gates.
- **GATE-6 "every case was proven"**: webhook genuine-delivery case NOT proven (their own acceptance used synthetic events signed locally — payment_events evt_acceptance_* IDs prove it).
- **Their N5 capacity doc** declared the sweep non-binding and demanded a full repeat — never re-run; my bounded measurements cover only single-stack reads; Black Friday capacity remains INSUFFICIENT EVIDENCE for both of us, but MY finding adds: capacity is moot while ORCH-1 stands.
- **Their coverage gate (GATE-1, 1483 files "reviewed")** — a coverage COUNT is not a correctness review; my per-area deep reads produced 96 concrete findings including 2 P0s their 1483-file review pass never surfaced (ORD-1, ORCH-1 were invisible to their gates).
- **Their harness-run CI jobs** (test-results/*.json PASS files) are pattern-selected self-reporting suites (QA-3) — this audit reran the real reactor independently.

## What this audit adds that the harness never had
1. ORCH-1 P0 breaker kill-switch (mechanism + live repro + Black Friday linkage).
2. ORD-1 P0 money-kept-on-cancel (with QA-verified absence of any refund path).
3. Live invariant races: quota storm 5/200 exact; oversell 5/30 exact; ghost-order residue (4/20) — none existed in their evidence.
4. Gateway/service public-path split-brain (anonymous search 401).
5. Alerting deployed-state failure (telegram-disabled with 5 criticals firing) + false-positive DB/Redis alerts.
6. Test-forensics: QA-1 dead ITs (live-verified), QA-2/3 gate reality — their green JSONs explained.
7. Honest unverified list: K8s runtime (no cluster), webhook delivery leg, multi-instance runtime, sustained/soak load.
