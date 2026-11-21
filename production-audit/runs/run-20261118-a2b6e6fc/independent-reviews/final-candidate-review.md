Verdict: FAIL

# Final independent acceptance review of the candidate on the branch now - run-20261118-a2b6e6fc

- **Reviewer:** independent acceptance reviewer (subagent qa-final-reviewer), reporting to lead.
- **Repository:** /Users/tonminh-mac/IdeaProjects/untitled5
- **Branch:** audit/production-hardening-run-20261118-a2b6e6fc
- **Candidate reviewed:** HEAD **f2421582** ("fix(audit): record the real telegram delivery and the stripe acceptance evidence"). The previous review examined 7486752e. The fix commits examined here are 450195e7, a0873067, 8cc700d1, c3bd495a, d5c1a372, 7e18740b and f2421582.
- **Method:** read-only static review plus two read-only executions: (1) the harness source_fingerprint(), and (2) evaluate_gates() + completion_predicate() of the **committed** harness code against a **clean clone of HEAD** at /tmp/qa-acceptance-head. **No Maven build was run**, **no Docker command was run**, **no git write command touched this repository**, and **no source file was edited**. The only file this review writes is this deliverable.
- **Impact analysis:** node .gitnexus/run.cjs impact <symbol> --direction upstream --repo . was run for every Java symbol cited; results quoted per finding. applyProviderRefund and releaseFailedClaims are **not in the graph** (the index predates the newest commits); their callers were resolved by text search. The Python and k6 harness is not in the Java graph; callers resolved by text search.

---

## 1. Summary

The money paths are now sound in source, and most of the previous findings are genuinely repaired: the provider webhook refund is reconciled from the ledger under the same row lock as the API refund (F1), the load pool is gated on the tokens actually minted and actually used (F2), the shed classification is 429/503 only and every 5xx is booked as a platform error that fails the reconciliation (F5), the ratio denominator is per profile and honest (F7), the entity width matches the migration (F8), and clearCart takes the write lock behind a test that can fail (F4).

The candidate nevertheless must not be released. The decisive defects are about **what the branch actually carries and what its own gates say about it**:

| # | Defect | Severity |
| --- | --- | --- |
| N1 | The committed run evidence claims production_ready: true while the committed harness, evaluated on the committed tree, computes **false** with 6 failing gates - a false pass inside the candidate itself | CRITICAL |
| N2 | The Stripe acceptance evidence (the centerpiece of this round) is **not committed**: zero tracked files under runs/run-20261118-a2b6e6fc/stripe-acceptance/, so the committed candidate has GATE-6 BLOCKED and the committed final report still says the real Stripe sandbox is BLOCKED | CRITICAL |
| N3 | F6 is not fixed: GATE-22 still cannot pass for a committed candidate. The manifest committed in f2421582 records revision c3bd495a (not HEAD); the manifest that matches HEAD exists only as an **uncommitted** working-tree file | HIGH |
| N4 | The F9 fix is in the source, but the only executable proof of it (OrderReturnConcurrentReviewIT) was **deleted** in 8cc700d1 with no replacement, no recorded run, and a commit message that calls the deletion a repair | HIGH |
| N5 | The recorded load sweep does not support a release: flash-sale FAIL (791 failures, 4267 dropped iterations), spike FAIL (ratio 0.0232, 5339 dropped iterations), promotion-storm BLOCKED (681 expired credentials); the capacity document itself declares the sweep non-binding and demands a full repeat | HIGH |
| N6 | The committed capacity state still publishes the contradictory pair F3 was supposed to remove: limit 13.9867 req/s (baseline) below the highest broken rate 21.5188 req/s (flash-sale) | MEDIUM |
| N7 | The working tree deletes five prior independent-review artifacts, uncommitted; committed, this would change the input of GATE-19 | WATCH |

GATE-19 is FAIL right now because four committed reviews carry FAIL verdicts; per the rule of this audit, a FAIL verdict must be answered by fixes and a new review, not by argument.

---

## 2. The nine previous findings, one by one

### F1 - Provider webhook double counting - **FIXED (verified in source, in tests, and against real Stripe)**

- payment-service/src/main/java/com/shop/paymentservice/service/ProviderRefundApplier.java:36-78 - the new single writer: @Transactional, locks the payment row through findByIdForUpdate (lines 80-89), reads the recorded amount from the payment_refunds ledger (lines 91-96), computes increment = providerRefunded - recorded, clamps a negative increment to zero with a warning (lines 50-55), writes a ledger row keyed webhook-<eventId> (lines 57-65), and sets the payment column from recorded + increment (lines 66-72).
- payment-service/src/main/java/com/shop/paymentservice/repository/PaymentRepository.java:24-27 - the lock is PESSIMISTIC_WRITE with a 10 s timeout, the **same** lock the API path takes (PaymentServiceImpl.requirePaymentForUpdate, PaymentServiceImpl.java:338-346). The two writers are serialised against each other.
- payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java:155-157 - every refund-class event is routed through the applier; lines 211-221 map the cumulative Stripe amount_refunded into the payload the applier reconciles against. The cumulative-minus-ledger arithmetic is idempotent: a duplicate or retried delivery recomputes increment 0 and writes no row.
- payment-service/src/main/resources/db/changelog/changelog-012-payment-refunds.yaml:30-31 - the unique index uk_payment_refunds_payment_reference backstops the event-keyed reference. The MoMo and VNPay callbacks never carry refund statuses (PspCallbackController.java:81-99 maps only CAPTURED and FAILED), so no other provider payload feeds the applier.
- Impact: impact ProviderRefundApplier - not found (index stale; callers resolved by text: WebhookEventService.process line 156, reached from the webhook controllers). impact WebhookEventService --file payment-service/.../WebhookEventService.java - risk **MEDIUM**, 7 direct upstream symbols. impact findByIdForUpdate --file payment-service/.../PaymentRepository.java - LOW, 3 impacted. impact refundConfirmed - LOW, 2 impacted. impact requirePaymentForUpdate - LOW, 2 impacted.
- Tests: WebhookRefundLedgerIT (payment-service/src/test/java/com/shop/paymentservice/WebhookRefundLedgerIT.java:66-133) covers exactly the case the previous review said was missing - the platform refund followed by the cumulative provider event - plus the difference case, the closing case and the platform-never-paid case. It **ran**: test-results/integration.log:20078 - "Tests run: 4, Failures: 0" at 18:31:44Z. The unit guard WebhookEventServiceStripeTest.java:472-514 now builds the payment WITH a refunded amount and a ledger row and asserts no double count and no spurious ledger row.

### F2 - Customer pool silently short - **FIXED**

- production-audit/harness/loadtesting/customers.py:188-231 - mint_tokens no longer swallows failures: it returns requested, minted and failed.
- production-audit/harness/loadtesting/runner.py:304-318 - the runner refuses to start when the provisioned pool is smaller than the virtual users: _customer_pool_violations -> enforce_safety(profile, provisioned_customers=...) (profiles.py:206-237) -> the profile returns **BLOCKED** before k6 runs.
- runner.py:174-192 - _customer_pool_shared additionally blocks a run when the summary of the load generator says it used fewer tokens than virtual users (customer_tokens_in_load_generator, recorded at runner.py:389 from k6-scenario.js:455).
- Recorded proof that the gate fires: load-results/promotion-storm-1795269071/result.json - pool 400/400, share_factor 1.0, one_customer_per_virtual_user true, and the run still BLOCKED, this time on 681 expired credentials. The harness refused to grade a compromised measurement instead of scoring it.

### F3 - Capacity verdict contradicting its own table - **PARTLY FIXED; the committed state still contradicts itself (N6)**

- production-audit/harness/loadtesting/capacity.py:244-257 still selects the clean row with the highest **delivered** request rate, and :260-261, :342 the broken rows likewise. The methodological mixing of delivered rates across scenarios is unchanged.
- What is new and real: capacity.py:315-331 (_binding) plus report sections 6 and 7 (capacity.py:567-604) declare, from recorded data, when the measurement **does not bind** to the current source (missing harness fields, harness or service commits after the window) and demand the sweep be repeated. The newest recorded document load-results/capacity-limit.md:103-121 says exactly that ("**No.** ... The whole sweep of this run must be repeated") and its numbers are internally consistent (limit ramp 42.24 delivered, highest broken flash-sale 21.52).
- The **committed** state still carries the old pair: git show f2421582:production-audit/state/performance-baselines.json -> capacity_limit.capacity_limit.profile = baseline (13.9867 req/s delivered) with stops_holding_at.highest_broken = flash-sale 21.5188 req/s - a limit below a rate the platform is recorded to have broken at, the exact defect F3 named. It is self-marked binds_to_current_source false, and the committed GATE-17 is stale/PENDING, so no gate passes on it - but the contradiction is still published by the candidate. capacity-limit.md is not tracked at all (zero tracked files).

### F4 - clearCart without a lock - **FIXED**

- order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:130-136 - clearCart now reads the cart through findByUserIdForUpdate and refuses when the cart is absent; every mutation of the cart of the user now holds the write lock.
- The guard can fail: order-service/src/test/java/com/shop/orderservice/service/impls/CartClearLockIT.java:30-67 holds the row lock in a second transaction and asserts clearCart is **not** done after 800 ms - with the old unlocked finder this assertion fails. It ran: integration.log:13423 - "Tests run: 1, Failures: 0".
- Residual (LOW, new finding): mergeCart still mutates the **guest** cart without a lock (CartServiceImpl.java:144-178 - guest items read with the unlocked findByUserIdAndDeletedFalse, then deleted, guest cart marked deleted). Two concurrent logins merging the same guest cart can double the merged quantities. No money moves; the order re-prices from the product service at confirmation.
- Impact: impact clearCart --file order-service/.../CartServiceImpl.java - LOW, 1 impacted; interface-bound callers not traced (CartController via DI).

### F5 - Every 5xx booked as a shed - **FIXED**

- load-tests/k6-scenario.js:37-43 - isShedStatus is now exactly 429 or 503, the statuses the gateway itself answers; isServerError covers every other 5xx, and observe() (lines 74-81) increments platform_error_total for each.
- k6-scenario.js:34-35 - the expected statuses are 200, 201, 400, 404, 409, 429, 503, so a 500 still lands in http_req_failed and the k6 transport threshold keeps its teeth. Every response is observed (a test asserts the request and observe counts are equal).
- production-audit/harness/loadtesting/runner.py:478-514 - _reconcile_with_load_generator fails the run when platform errors exceed the booked business failures ("a server error left the business failure budget") or when the two accountings disagree; :517-541 runs it inside the acceptance check and :535-538 records platform_error_rate. The recorded results carry the field (capacity-limit.md:48-55).
- The guard is wired to the real sources: production-audit/harness/tests/test_load_accounting.py:28-67 parses k6-scenario.js and the gateway writer and fails if the shed set is widened beyond the statuses of the gateway, and :93-124 fails if a platform error escapes the failure budget. These are tests that can fail.

### F6 - The manifest gate - **NOT FIXED (N3)**

- production-audit/harness/verification/gates.py:506-524 - GATE-22 still requires manifest.revision == HEAD **and** manifest.source_fingerprint == the working tree. The revision half is structurally unsatisfiable for a committed candidate: the manifest cannot contain the hash of the commit that carries it.
- Measured on the committed candidate: the manifest committed in f2421582 records revision c3bd495a8ca5... and fingerprint d25673c3... (git show f2421582:production-audit/control/source-manifest.json). HEAD is f2421582 and the fingerprint of the committed tree is 3094513e... (the value every committed suite record carries). GATE-22 is therefore **PENDING** on the committed candidate - confirmed by running the committed harness on a clean clone of HEAD.
- A manifest that does match (revision f2421582, fingerprint 6ff396f075a31e4b9c0fa133434025fbc5a4dbff0c0bf2f87bdae36e769c3db2 - reproduced with the harness source_fingerprint()) exists **only as an uncommitted working-tree file**, and its fingerprint covers two **untracked** files (independent-review/run-20261122-f242158/baseline/*.json) that no release would contain. Committing it re-stales it. The gate can be made green only by a design change: drop the revision equality, or compare the evidence against its own recorded revision.

### F7 - Ratio denominator - **FIXED**

- production-audit/harness/loadtesting/capacity.py:136-139 - _rate_ratio_denominator names planned_iteration_rate_per_second for documents that carry the planned rate and target_requests_per_second only for the runs of the pre-fix harness; :170-171 records it per profile; report section 5 (capacity.py:536-565) states the unit conditionally and prints a denominator per profile instead of the previous unconditional and false sentence.

### F8 - Entity column width - **FIXED**

- payment-service/src/main/java/com/shop/paymentservice/entity/Payment.java:35 and :39 - status and previous_status are now length = 32, matching changelog-013; PaymentEvent was widened in the same commit (a0873067). The migration and the mapping can no longer disagree.

### F9 - Concurrent approve and reject of a return - **FIXED IN SOURCE, BUT ITS TEST WAS DELETED (N4)**

- order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java:148-202 - reviewReturn first resolves the orderId, then locks **all** returns of the order (repository OrderReturnRepository.java:22-25, PESSIMISTIC_WRITE, 10 s timeout, ordered by id to avoid deadlocks), and the return under review is taken **from the locked list** - so the second reviewer always re-reads the status after the first commits and is refused with order.return.already.reviewed (lines 157-159). The money still moves once for a repeated reference: the provider key is the return id and PaymentServiceImpl.java:199-203 returns the recorded outcome.
- Impact: impact reviewReturn --file order-service/.../OrderReturnServiceImpl.java - LOW, 1 impacted, 2 interface-level consumers. impact findByOrderIdForUpdate --file order-service/.../OrderReturnRepository.java - LOW, 3 impacted.
- **The defect:** commit a0873067 added OrderReturnConcurrentReviewIT - a real race test (latch, 600 ms scripted refund, two racing reviews, asserting exactly one decision and REFUNDED on the row). Commit 8cc700d1, whose message says "repair the two tests the webhook and cart fixes left behind", **deleted it** (131 lines removed), changed one Mockito stub to lenient(), and left no replacement: no test in the tree drives two concurrent reviews, and a repository-wide search for OrderReturnConcurrentReviewIT returns nothing. The test never appears in any recorded suite. The serialisation is argued here from the source, but the candidate no longer carries executable proof of F9, and the commit message misrepresents a deletion as a repair.

---

## 3. The money paths under concurrency

- **Refund:** the API path locks the payment row (PESSIMISTIC_WRITE), dedupes on the ledger reference, reconciles the amount from the ledger sum and writes a ledger row (PaymentServiceImpl.java:187-268); the webhook path is the same ledger under the same lock (ProviderRefundApplier). Two racing API refunds: RefundConcurrencyIT - integration.log:19952, "Tests run: 5", all passing. Two racing writers (API and webhook): serialised by the same row lock, covered by WebhookRefundLedgerIT (4 tests, log:20078).
- **Order confirmation and payment capture:** payment create is idempotent on a unique key with a unique-violation replay (PaymentServiceImpl.java:87-117); capture dedupes through the provider key; PaymentIdempotencyIT ran (log:18793, "Tests run: 5") and the Stripe acceptance case refund_idempotent_replay is PROVEN against api.stripe.com.
- **Inventory reservation:** atomicReserve is a single conditional UPDATE gated on (availableQuantity - reservedQuantity) >= :qty (inventory-service/.../InventoryRepository.java:19-22) - the database rejects the oversell - and the reservation row replays only with the same product and quantity (InventoryServiceImpl.java:138-181).
- **Idempotency claim:** insertIfAbsent in a REQUIRES_NEW transaction; replay only after a recorded response and only for the same request hash (order-service/.../IdempotencyServiceImpl.java:48-101); IdempotencyClaimIT ran in the recorded suite (log:16137 ff).
- **Outbox:** the payment relay claims one aggregate per transaction (PaymentOutboxRelay.java:115-150) behind pg_try_advisory_xact_lock(hashtextextended(aggregate_id)) plus FOR UPDATE SKIP LOCKED plus a no-SENDING-row-for-this-aggregate guard (OutboxEventRepository.java:24-38), releases claims back to PENDING when the broker fails (relay :152-164, repository :58-72) and recovers stranded leases on its own schedule (:196-208). The same shape exists in the other services: releaseFailedClaims is present in all eight relays, and shipping claims one event per transaction with SKIP LOCKED. Recorded proof: PaymentOutboxClaimConcurrencyIT, PaymentOutboxAggregateOrderIT, PaymentOutboxRelayRecoveryIT, PaymentOutboxStrandedClaimIT and the equivalents in order, shipping, rating, promotion, product, media and inventory all ran with 0 failures in the recorded integration suite (log:16787-20078; test-results/integration.json: 362 tests, revision 7e18740b). The Java sources are byte-identical from 7e18740b to HEAD, and the suite fingerprints recorded in production-audit/state/verification.json match the committed tree exactly.
- Impact for the cited outbox symbols: impact claimAggregateEvents --file payment-service/.../OutboxEventRepository.java - LOW, 2 impacted. impact PaymentOutboxRelay - LOW, no direct caller (scheduled entry point).

The one money-adjacent path with **no executable race proof left** is the return review (N4/F9).

---

## 4. The new claims of this round

- **Real Stripe acceptance.** The working-tree evidence (production-audit/runs/run-20261118-a2b6e6fc/stripe-acceptance/: server.json, acceptance.json, cli-result.json, README.md) is detailed and internally consistent: stripe_api_base_of_record https://api.stripe.com, a credential probe of /v1/balance answered 200 with livemode false, secret_key_mode "test" with an sk_test_ prefix recorded as fingerprints (not the key), 8 of 8 cases PROVEN including declined_card_refusal (402 card_declined, generic_decline, against pm_card_visa_chargeDeclined) and refund_idempotent_replay. Two honest caveats: (1) the recorded request headers show Host host.docker.internal:12113 - the traffic went through a local wire recorder, so the TLS peer cannot be proven from the files alone; (2) **none of it is committed** - git ls-files of the stripe-acceptance directory returns zero files. On the committed candidate load_evidence() finds nothing and GATE-6 is **BLOCKED** ("no Stripe payment acceptance evidence"), and the committed final report (production-audit/reports/final-production-readiness-report.md, section 1) still says "Stripe sandbox thuc: trang thai BLOCKED". The headline result of this round is not part of the candidate.
- **Real Telegram delivery.** production-audit/state/alert-results.json **is committed** and records six gates PASS at 17:37:30-48Z against https://api.telegram.org: the bot identity probe (bot 8687242537, ecommerceminhBot), the CI route, the runtime route, recovery, grouping and redaction, with Telegram message ids 8-10 and dates inside the responses. Chat ids are stored only as sha256 fingerprints, so the raw destinations cannot be re-derived from the evidence - a deliberate redaction, not a defect. This claim holds on the committed candidate.
- **Keycloak token lifetime.** The lifetime is not fixed in the realm import (docker/keycloak/import/ecommerce-realm.json has no accessTokenLifespan); the harness enforces it per run: ensure_token_lifetime(seconds = duration_seconds + 600) before provisioning (runner.py:260-273), BLOCKED when the realm cannot be read or written, and a run in which credentials expired is **BLOCKED**, not failed (_credentials_expired, runner.py:466-475, wired into _final_status at :438-441). Recorded proof: promotion-storm-1795269071 BLOCKED with 681 credential errors - exactly the demanded behaviour.
- **Outbox relay.** Verified as described in section 3; "one aggregate per transaction" holds literally for payment (per-aggregate claim) and per event for order and shipping, both recorded under race ITs.

---

## 5. The gates, evaluated on the committed candidate

I ran the **committed** harness code against a **clean clone of HEAD** (no working-tree files). The result, from completion_predicate:

- production_ready: **False**; failing gates: **GATE-6, GATE-17, GATE-19, GATE-22, GATE-24, GATE-25**.
- GATE-6 BLOCKED - "no Stripe payment acceptance evidence" (N2). GATE-17 PENDING - the committed performance suite fingerprint (d25673c3) does not match the committed tree (3094513e). GATE-19 FAIL - "4 of 6 independent reviews report a failing verdict". GATE-22 PENDING - the stale manifest (N3). GATE-24 and GATE-25 BLOCKED - no autoscaling observation committed.
- The other 19 gates PASS on the committed evidence: the suite records in production-audit/state/verification.json are fresh against the committed tree. This is the strongest positive signal of the round - the 362-test integration suite, the unit, security, e2e, k8s and alerts suites and the comment gate are genuinely bound to the released commit.
- **N1 - the false pass inside the candidate:** the committed run evidence production-audit/runs/run-20261118-a2b6e6fc/verification.json states "production_ready": true with an empty failing-gates list, evaluated at 2026-11-21T18:34:13Z - while the committed harness, on the committed evidence, computes **false** with six failing gates, and the committed final report itself concludes "CHUA DAT". A candidate whose recorded completion predicate contradicts its own gate engine and its own report cannot be released: the product of this audit is evidence, and this evidence is self-refuting.

---

## 6. The load evidence

- Newest recorded sweep (working tree): **ramp PASS** (3907 successes, 0 failures, 0 shed, ratio 0.819, delivered 42.24 req/s) - the first full-scenario profile that passes - and baseline and recovery PASS (browse). flash-sale **FAIL** (791 business failures, 783 shed, 4267 dropped iterations), spike **FAIL** (ratio 0.0232, 32 failures, 68 shed, 5339 dropped iterations), promotion-storm **BLOCKED** (expired credentials). The capacity document (capacity-limit.md:103-121) declares the sweep non-binding and demands the whole sweep be repeated with the current harness. Until that repeat exists, there is no passing load evidence for the order-placing profiles at their planned rates, and the recorded dropped-iteration counts show that the measurement host itself could not offer the planned rates.

---

## 7. What I could not check

1. **I did not run the build or the tests.** Every statement about a test is about its source and the recorded surefire/failsafe log (integration.log, 11:30 min, BUILD SUCCESS, 362 tests, 0 failures, revision 7e18740b). I did not observe the locks, the races or the webhook at runtime.
2. **I did not exercise Stripe or Telegram.** The Stripe evidence was not re-driven against api.stripe.com (no key in my scope), and because the recorded wire passed through a local recorder (host.docker.internal:12113) the TLS peer cannot be proven from the files; the credential probe, the request ids, the decline behaviour and the key fingerprints are consistent with a genuine test-mode run. The Telegram deliveries are taken from the recorded Bot API responses; the chat ids are hashed and cannot be re-derived.
3. **The uncommitted working tree.** A load sweep, a manifest regeneration, coverage updates, runner.py edits and the deletion of five prior review artifacts (PAY-15-30-psp-integration-review-2 and -3, PAY-15-psp-integration-review, final-change-review, high-batch-acceptance-criteria, orchestrator-change-review - all under independent-reviews/) are uncommitted. I reviewed the branch as committed; whether and in what shape that work lands is outside this review. The deletions, if committed, change the inputs of GATE-19 and must not be made silently.
4. **GitNexus index freshness.** applyProviderRefund, releaseFailedClaims and ProviderRefundApplier are absent from the graph (the index predates the newest commits); their callers were resolved by text search. impact WebhookEventService reports risk **MEDIUM** with 7 direct callers - quoted above.
5. **The 1483-file coverage record** was not re-verified file by file; the committed GATE-1 passes on it and I did not re-audit the coverage states themselves.
6. **F9 at runtime.** With OrderReturnConcurrentReviewIT deleted, no runtime proof of the approve/reject serialisation exists anywhere; my verification of F9 is a source-level argument only.

---

## 8. What would change this verdict

1. Commit the Stripe acceptance evidence and the regenerated reconciliation state, so that GATE-6 and GATE-22 evaluate on the released revision (GATE-22 additionally needs its revision-equality redesign, or an explicit evidence-revision pointer).
2. Replace the recorded production_ready: true in the run evidence with the predicate the committed harness actually computes, or regenerate it at the final revision - no false pass may remain in the released evidence.
3. Restore an executable F9 race test (re-add OrderReturnConcurrentReviewIT or an equivalent), with a commit message that says what happened to it.
4. Repeat the load sweep with the current harness, as the capacity document itself demands, including the profiles that place orders.
5. Re-run this review on the new final revision.
