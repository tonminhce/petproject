# PAY-15 / PAY-30 independent review — MoMo and VNPay PSP integration (review 2)

**Task ids reviewed:** PAY-15, PAY-30 (production-audit/state/tasks.json)
**Commits reviewed:** 33976ab9, ef9f3c1c, 167c9909 (branch audit/production-hardening-run-20261118-a2b6e6fc, HEAD cac13c9f)
**Area:** payment-service (provider, config, webhook), plus the order-service captured-payment gate and the deployment manifests
**Reviewer:** Verifier (independent QA gate), run run-20261118-a2b6e6fc
**Date:** 2026-11-19
**Previous verdict:** QA_FAIL (production-audit/runs/run-20261118-a2b6e6fc/independent-reviews/PAY-15-psp-integration-review.md)

**Verdict: QA_FAIL**

The three commits genuinely close D1, D3, D4, D5, D6 and D7 — the MoMo canonical string, the refusal outcome, the
callback path and the production guard are now correct and I re-verified them independently. D2 (the MoMo refund
confirming on an unverified response) is only narrowed, not closed: a refund response body of {"resultCode":0} with
no orderId, no amount and no signature still yields CONFIRMED, reproduced live against the committed classes. The
VNPay hash now follows the url-encoded convention of the official Node.js sample, but the encoder used is
java.net.URLEncoder, which diverges from that sample on five characters (! ' ( ) ~), so the hash silently disagrees
with the provider for any parameter value containing them. Three further money-path defects were found (a
non-success notification permanently bricking the payment, a MoMo callback field-set assumption, and a wrong
notify URL in the committed env example).

---

## 1. Acceptance criteria under review (restated from the ledger)

### PAY-15 — "VNPay multiplies the amount by 100 unconditionally and neither provider ever contacts the PSP"
1. "Zero-decimal currencies are not multiplied"
2. "A provider that is not integrated is not selectable in production"

### PAY-30 — "The non-Stripe webhook path cannot verify a real MoMo or VNPay IPN"
1. "A MoMo IPN is accepted only when its signature verifies against the documented MoMo field set"
2. "A VNPay IPN is accepted only when vnp_SecureHash verifies over the decoded parameters"
3. "The callback translates the PSP payload into the carrier payload so the payment reaches CAPTURED and the order captured-payment gate opens"
4. "The callback endpoints are public, size limited and rate limited like the Stripe path"
5. "A duplicate callback does not apply the state transition twice"
6. "A callback whose amount differs from the captured amount is rejected"

PAY-15 criterion 1 keeps the wording caveat raised in the first review: VND is a zero-decimal currency and the
VNPay wire format is minor units (vnp_Amount = amount x 100), so the criterion cannot hold literally. This
implementation now refuses any currency the PSP does not support instead of mis-scaling it, which is what the
criterion protects against, and I keep the prior review's reading: satisfied in substance.

---

## 2. What I read

payment-service main
- provider/MoMoProvider.java (284 lines), provider/VNPayProvider.java (227), provider/PaymentProvider.java,
  provider/PaymentProviderConfig.java, provider/PaymentProviderFactory.java, provider/MockProvider.java,
  provider/StripeProvider.java, provider/CodProvider.java
- config/PaymentMoMoProperties.java, config/PaymentVNPayProperties.java, config/MoMoProviderConfiguredCondition.java,
  config/VNPayProviderConfiguredCondition.java, config/PaymentProviderProductionGuard.java,
  config/PaymentPspRestClientConfig.java, config/PaymentStripeProperties.java
- webhook/PspCallbackController.java, webhook/MoMoCallbackVerifier.java, webhook/VNPayCallbackVerifier.java,
  webhook/PaymentWebhookController.java, webhook/WebhookPayload.java
- service/WebhookEventService.java, service/PaymentStateMachine.java, service/PaymentWriter.java,
  service/impls/PaymentServiceImpl.java, service/PaymentService.java
- outbox/PaymentEventPublisher.java, entity/PaymentEvent.java, entity/Payment.java, constant/PaymentStatus.java
- resources/application.yml, resources/db/changelog/changelog-001-payments.yaml (and -003, -007)

payment-service tests
- provider/MoMoProviderTest.java, provider/VNPayProviderTest.java, provider/PaymentProviderRegistrationTest.java,
  provider/PaymentProviderConfigTest.java, provider/PaymentProviderRoutingTest.java,
  config/PaymentProviderProductionGuardTest.java, webhook/PspCallbackControllerTest.java,
  webhook/PaymentWebhookControllerTest.java, service/WebhookEventServiceTest.java,
  PaymentCaptureConfirmationIT.java, StripeWebhookIT.java, CapturedPaymentLookupIT.java

Deployment / other services
- .env.example, docker-compose.yml, docker-compose.prod.yml, k8s/microservices.yaml, k8s/secrets.template.yaml
- order-service: OrderServiceImpl.java, PaymentServiceClient.java, PaymentGateValidator.java,
  kafka/PaymentLifecycleConsumer.java
- gateway-service: application.yml, ratelimit/GlobalRateLimitFilter.java, ratelimit/GlobalRateLimitProperties.java
- utils/common-core: ApiPaths.java

Reference
- production-audit/runs/run-20261118-a2b6e6fc/independent-reviews/PAY-15-psp-integration-review.md (the prior QA_FAIL)

---

## 3. What I executed

| Command / probe | Result |
| --- | --- |
| git log / git diff --stat 33976ab9~1..167c9909 | 23 files, +2502/-101; only payment-service, .env.example, docker-compose.yml, docker-compose.prod.yml, k8s/microservices.yaml and audit bookkeeping. No order-service file is touched, so the captured-payment gate is pre-existing and unchanged. |
| ./mvnw -B -ntp -pl payment-service test | BUILD SUCCESS — "Tests run: 257, Failures: 0, Errors: 0, Skipped: 0" (1:34 min) |
| ./mvnw -T1C -pl payment-service validate | BUILD SUCCESS — "You have 0 Checkstyle violations." |
| /tmp/pspcheck/VerifyPsp.java (scratch, outside the repo; drives the committed MoMoProvider/VNPayProvider against a local HTTP server and MockRestServiceServer) | Captured the real MoMo create request body and the real VNPay redirect URL; confirmed the MoMo refund hole (see F1) |
| /tmp/pspcheck/VerifyPsp2.java (scratch; drives MoMoCallbackVerifier / VNPayCallbackVerifier) | Round trip, tamper rejection, and the MoMo field-set variants (see Q3, Q4, F4) |
| /tmp/pspcheck/VerifyPsp3.java (scratch; documented error response, refund response signed over the response field set, VNPay refund form body) | REJECTED/MOMO_RESULT_CODE_1002; MOMO_REFUND_SIGNATURE_INVALID; VNPay refund CONFIRMED with vnp_SecureHash present |
| Independent Python recomputation (hashlib) of the MoMo create signature and of the VNPay hash over the Java-produced artefacts | MoMo signature identical to the Java output; VNPay hash identical under the url-encoded convention, different under the raw convention (see Q1, Q3) |
| Java vs Node encoder comparison (/tmp/pspcheck/Enc.java + node encodeURIComponent) | Java a%21b%27c%28d%29e%7Ef+g vs Node a!b'c(d)e~f+g — five characters diverge (see F2) |
| web_fetch developers.momo.vn/v3/docs/payment/api/result-handling/notification/ | 403 Forbidden (nginx bot protection). The IPN example body was obtained through web_search instead; the official vnpay.github.io sample paths returned 404 through three different routes |
| web_search for the official VNPay Node.js sample | The sample helper was reproduced verbatim by an independent third-party repository (github.com/nhan7tuoi/pay-sandbox, docs/vnpay.md and test-gateways.ts), which states it is "the same helper as VNPay's official Node.js sample" |

No project file was modified. All scratch artefacts are in /tmp/pspcheck and /tmp/paycp.txt.


---

## 4. Answers to the seven review questions

### Q1 — Are D1/D2/D4 genuinely fixed? Is exactly what the provider signs signed, including the empty documented fields? Is a genuine refusal now REJECTED?

**D1: fixed. D4: fixed. D2: narrowed, not fixed.**

Canonical string (create), MoMoProvider.java:73-83 — the sorted k=v set is exactly the documented one and the
empty extraData participates:
accessKey=ACCESS&amount=50000&extraData=&ipnUrl=...&orderId=...&orderInfo=Payment&partnerCode=PARTNER&redirectUrl=...&requestId=...&requestType=captureWallet

I recomputed that string independently in Python (hashlib HMAC-SHA256 over the documented field list) and the
Java signature produced by the committed provider is byte-identical: 8a14c1b803ea3435f8845e7713e5d196b52cf944ab0f58c5d5b496be6a089f2e.
The payload actually sent carries extraData as an empty string (not omitted), and amount as an integer 50000, so
what is signed is what is transmitted. The refund canonical set (MoMoProvider.java:127-134) likewise includes the
empty description. The response canonical set (MoMoProvider.java:248-257) includes payUrl even when the field is
absent, which is the fix for the error path.

Refusal outcome (D4): MoMoProvider.java:199-205 checks the response signature first and then the resultCode, so
the order of the checks no longer matters. My probe fed a documented error response (resultCode 1002, signed over
the documented response field set including the empty payUrl) and the result was:
outcome=REJECTED eventId=MOMO_RESULT_CODE_1002
The response is now a genuine rejection, not an unknown outcome, so PaymentServiceImpl.java:135 raises
PAYMENT_PROVIDER_REJECTED rather than SERVICE_UNAVAILABLE.

**D2 is not closed.** MoMoProvider.java:167-174 binds the refund response to the request only opportunistically:
an absent orderId passes, an absent amount passes, and MoMoProvider.java:178-181 treats an absent signature as
valid. A body of {"resultCode":0} therefore confirms the refund. Live probe against the committed classes:
outcome=CONFIRMED eventId=144492817
That confirmation flows into PaymentServiceImpl.java:176-182, which marks the payment REFUNDED and publishes
payment.refunded.v1 for a refund the PSP never performed. The first review listed as required evidence "a test
proving a response with a wrong or absent signature never yields CONFIRMED"; the committed code still yields
CONFIRMED for an absent signature, so that evidence is not met. See F1.

### Q2 — Is the MoMo refund confirmation bound to the request it belongs to? Can any body still produce a false refund confirmation? Is the compromise defensible?

The compromise is defensible in direction and incomplete in effect. The documented MoMo refund response carries no
signature field, so there is nothing to verify cryptographically; checking the request-bound fields when the
provider returns them (orderId, amount, and a signature if one ever appears) is the right shape of defence, and
MoMoProvider.java:154-159 applies it before the resultCode is read. The gap is that "when the provider returns
them" is not required: the implementation must reject or hold a resultCode=0 response that carries no evidence of
belonging to the request. As written, any body of {"resultCode":0} confirms, which is the original D2 defect
with a narrower trigger. See F1.

A second, latent problem in the same area: MoMoProvider.java:176-192 verifies a refund signature over the
REQUEST canonical set (accessKey, amount, description, orderId, partnerCode, requestId, transId), while a
signature on a refund RESPONSE would follow the documented response set (accessKey, amount, message, orderId,
partnerCode, requestId, resultCode, transId). Probe: a refund body signed over the documented response field set
produced outcome=INDETERMINATE eventId=MOMO_REFUND_SIGNATURE_INVALID. The branch is dead today because the
documented refund response has no signature, so nothing is lost — but the code claims a verification it cannot
perform. See F5.

### Q3 — Is the VNPay hash now computed the way the official node sample computes it, for BOTH the redirect URL and the refund form body? Does the callback verifier use the same convention? Would a value with a space or a non-ascii character verify on both sides?

The convention now matches the official sample's shape: keys and values encoded, sorted by encoded key, stringified
without re-encoding, vnp_SecureHash and vnp_SecureHashType excluded. VNPayProvider.java:193-214 (sign) and
VNPayCallbackVerifier.java:30-44 use the same rule. I recomputed the hash over the URL the provider actually
produced, in Python, independently of the Java code: identical (cda6b950... when the value contained no special
characters), and different under the raw-value convention — so the fix is real, not cosmetic.

Both the redirect URL (VNPayProvider.java:96,180-191) and the refund form body (VNPayProvider.java:134,136-137)
carry a vnp_SecureHash computed by that same sign method; my probe confirmed the refund form body contains
vnp_SecureHash.

Spaces and non-ASCII verify on both sides. Probe with vnp_OrderInfo = "Thanh toán đơn hàng #1 (VIP)": the redirect
URL contained Thanh+to%C3%A1n+%C4%91%C6%A1n+h%C3%A0ng+%231+%28VIP%29, decoding to the original string, and
VNPayCallbackVerifier.verify returned true; a tampered vnp_Amount returned false. Both sides use the same encoder,
so the round trip is self-consistent.

But the encoder itself is not the official sample's encoder, and that is a real defect. The official Node.js
sample encodes with encodeURIComponent and only maps %20 to "+", leaving ! ' ( ) ~ literal; java.net.URLEncoder
percent-encodes all five. Measured on the same input:
  Java URLEncoder : a%21b%27c%28d%29e%7Ef+g
  Node sample     : a!b'c(d)e~f+g
Any vnp_* value containing one of those five characters produces a different canonical string and therefore a
different hash from the provider's. See F2 for the impact.

### Q4 — Is the callback path correct and safe? Does the MoMo verifier sign the fields the provider actually sent, and only those? Can a tampered body pass? Does the VNPay callback recompute the hash over the decoded parameters? Does an accepted callback move the payment to CAPTURED through the existing state machine and outbox, and does the order-side gate then open? Is the MoMo transaction id stored? Is the provider event id a sound de-duplication key?

Signature handling.
- MoMo (MoMoCallbackVerifier.java:36-63): the canonical string is built over the documented field list
  (MoMoCallbackVerifier.java:15-28) in alphabetical order, with accessKey taken from the merchant configuration
  rather than from the body, and fields absent from the body skipped. The official MoMo v3 IPN body carries
  exactly orderType, amount, partnerCode, orderId, extraData, transId, responseTime, resultCode, message, payType,
  requestId, orderInfo plus the signature, which together with accessKey is exactly the 13-field set the verifier
  uses. Probes: the 13-field body verifies true; a tampered amount verifies false; a body missing a field the
  provider had signed verifies false; the 8-field variant (accessKey, extraData, message, orderId, partnerCode,
  requestId, responseTime, resultCode) verifies true; but the 8-field variant plus unsigned extra fields
  (amount, transId) verifies FALSE. So the verifier is only correct while "the set the provider signed" equals
  "the set of fields present in the body". Fail-closed, but see F4 — the two documented variants cannot both be
  satisfied by one rule, and I could not settle which variant the live account uses (see the limits section).
- VNPay (VNPayCallbackVerifier.java:22-48): the decoded parameters are re-encoded canonically and the hash is
  recomputed; a tampered amount is rejected. The controller answers {"RspCode":"97"} on a bad hash and
  {"RspCode":"00"} on an accepted one (PspCallbackController.java:65,72), which is the documented acknowledgement.
- A tampered body cannot pass in either case: the HMAC covers every field that is mapped into the carrier payload,
  including amount, orderId and transId.

State machine, outbox and the order gate.
PspCallbackController.java:75-108 maps the PSP payload into the carrier payload (paymentId, amount, currency,
status, providerEventId, providerIntentId) and hands it to WebhookEventService.handle. The service
(WebhookEventService.java:148-183) loads the payment, rejects an amount mismatch with AMOUNT_MISMATCH
(:160-163), stores the provider intent id (:164-166), applies PaymentStateMachine.transition (PENDING to CAPTURED
is legal, PaymentStateMachine.java:13) and calls writer.completeWithEvent, which marks the event PROCESSED, saves
the payment and publishes the outbox event payment.captured.v1 (PaymentWriter.java:62-68,
PaymentEventPublisher.java:28-37). The order service consumes that event
(order-service PaymentLifecycleConsumer.java:30-43) and calls confirmOrder, whose captured-payment gate
(order-service OrderServiceImpl.java:287-298, PaymentServiceClient.java:41-60) requires a CAPTURED payment for the
order. The chain is complete and uses only pre-existing machinery.

MoMo transaction id: PspCallbackController.java:89 puts transId into providerIntentId and
WebhookEventService.java:164-166 stores it on the payment, so the later refund finds it (PaymentServiceImpl.java:166-167
passes payment.getProviderIntentId() to the provider, and MoMoProvider.java:114-117 requires it to be numeric).
This closes the D3 refund blocker.

De-duplication key: sound. The event id is the provider transaction id (MoMo transId, falling back to requestId at
PspCallbackController.java:80; VNPay vnp_TransactionNo, falling back to the txn ref at :97). It is stable across
repeats, and the database enforces uniqueness with uk_payment_events_provider_event on (provider, provider_event_id)
(db/changelog/changelog-001-payments.yaml:73). WebhookEventService.java:90-94 returns early when the event is
already PROCESSED, so a replay is a no-op. The amount guard makes a second, differently-identified callback harmless.

### Q5 — Can an unintegrated or simulated provider still be selected in any deployment (compose, compose prod overlay, kubernetes)? Is the guard reachable in all three? Can a provider bean exist without full configuration?

No provider can be selected by name unless it is integrated, in any of the three deployments. The mechanism is bean
existence, not only the guard:
- MoMoProvider.java:29 and VNPayProvider.java:33 carry @Conditional on MoMoProviderConfiguredCondition /
  VNPayProviderConfiguredCondition, which require the enable flag plus base URL, partner code, access key, secret
  key and both callback URLs (MoMoProviderConfiguredCondition.java:19-28, VNPayProviderConfiguredCondition.java:19-28).
  PaymentProviderRegistrationTest.java:44-122 proves a bean does not exist without the full configuration, including
  the callback URLs.
- MockProvider.java:10 only creates the mock bean when the default provider is mock.
- StripeProvider.java:19 only creates the Stripe bean when the provider is stripe or a Stripe secret key is present.
- CodProvider is always registered; the guard explicitly allows cod (PaymentProviderProductionGuard.java:43-45).

So D5 is closed by construction: a request naming an unintegrated provider is refused at selection time with
PAYMENT_PROVIDER_REJECTED (PaymentServiceImpl.java:57-71) instead of failing later with a 503.

Guard reachability: production mode is a property (application.yml:37), the guard is conditional on it
(PaymentProviderProductionGuard.java:11) and it validates mock, cod, stripe, momo and vnpay (:33-59).
- compose (dev): production mode defaults to false (docker-compose.yml:353) — the guard is inactive by design.
- compose prod overlay: docker-compose.prod.yml:66 sets it to true — reachable.
- kubernetes: k8s/microservices.yaml:84-85 sets it to true for the payment-service — reachable. This closes D6.

D7 is closed: PaymentMoMoProperties.java:27-30 and PaymentVNPayProperties.java:37-40 now require the return URL and
the notify URL, and the conditions require the same set, so a bean cannot exist without them.

The cost of closing D6 is a deployment break — see F6.

### Q6 — Are the tests genuine — do they independently recompute the expected signature, or assert the implementation against itself? What regression could still slip through?

Mixed, and weaker in the callback area than in the provider area.

Genuine:
- MoMoProviderTest.java:235-248 writes the create canonical string out literally, field by field, and signs it with
  its own HMAC helper (:286-292). It does not reuse the provider's rule, so a change to the provider's canonical
  string fails the test. The response-signature and error-response builders (:250-284) do the same. This is the
  right shape and it is what let me treat D1/D4 as closed.
- PaymentProviderRegistrationTest.java and PaymentProviderProductionGuardTest.java drive real Spring contexts and
  assert bean presence/absence, not mock behaviour.
- The HMAC primitives themselves are pinned to published vectors (MoMoProviderTest.java:230-233, RFC 4231 in
  VNPayProviderTest.java:158-166).

Asserting the implementation against itself:
- PspCallbackControllerTest.java:157-170 builds the MoMo canonical string from MoMoCallbackVerifier.SIGNED_FIELDS —
  the implementation's own constant — and reuses the implementation's skip-absent rule. A change to the field list,
  its order, or the skip rule would not fail any test. The MoMo callback field set is therefore unpinned.
- VNPayProviderTest.java:168-191 and PspCallbackControllerTest.java:195-212 are second copies of the same
  encode-and-sort algorithm, not a published vector. A shared misreading of the vendor convention passes both —
  which is exactly what happened with the five-character encoder divergence in F2.
- VNPayProviderTest.java:114-130 asserts the refund form body contains vnp_Command and vnp_TxnRef but never
  asserts the vnp_SecureHash in that body, so the "both the redirect and the refund body" property is unpinned.

Gaps:
- No test exercises a value containing a space or a non-ASCII character through the redirect to callback round trip.
  I verified it manually; it works, but nothing guards it.
- No test exercises a value containing ! ' ( ) ~ — the F2 divergence would not be caught.
- No end-to-end integration test for the PSP path (contrast StripeWebhookIT.java, which covers capture, tamper
  rejection, replay de-duplication and idempotent create). The PSP callback to CAPTURED to outbox to order gate
  chain is covered only by unit tests.

### Q7 — Any remaining defect in the money path?

False confirmation: still possible on the MoMo refund (F1). No false confirmation is reachable on the create path
(the response must carry a valid documented signature and a matching amount) or on the callback path (the HMAC
covers every mapped field, and the amount is re-checked against the stored payment).

Lost callback: three routes — the VNPay encoder divergence (F2), the MoMo field-set assumption (F4), and the wrong
notify URL in the committed env example (F7).

Replay: covered — unique index plus the PROCESSED short-circuit.

Double refund: covered — PaymentServiceImpl.java:161-163 requires CAPTURED, and the state machine makes FAILED and
REFUNDED terminal (PaymentStateMachine.java:12-16).

Wrong amount: covered — MOMO_AMOUNT_MISMATCH on the create response (MoMoProvider.java:206-209), AMOUNT_MISMATCH on
the callback (WebhookEventService.java:160-163), and the VNPay x100 conversion is exact on both directions
(VNPayProvider.java:172-174, PspCallbackController.java:122-133, which refuses a value that is not a whole unit).

Provider selected without an integration: covered (Q5).

Not covered — F3: a single non-success PSP notification permanently bricks the payment.


---

## 5. Defects by severity

### HIGH

**F1 — The MoMo refund still confirms on a body that carries no evidence of belonging to the request.**
File: payment-service/src/main/java/com/shop/paymentservice/provider/MoMoProvider.java:167-174 (orderId/amount
treated as optional), :178-181 (an absent signature is accepted as valid), :160-164 (resultCode 0 becomes
ProviderResult.confirmed).
Trigger: a refund response body of {"resultCode":0} with no orderId, no amount and no signature.
Impact: the payment is marked REFUNDED and payment.refunded.v1 is published (PaymentServiceImpl.java:176-182) for a
refund the PSP may not have performed; the merchant absorbs the loss and the order-side refund path believes the
money came back.
Evidence: live probe against the committed classes — outcome=CONFIRMED eventId=144492817. The first review's
required evidence item 2 ("a response with a wrong or absent signature never yields CONFIRMED") is not met.
Preconditions for abuse: control of, or misdirection of, the configured provider base URL. That precondition is not
hypothetical in this repository — docker-compose.yml:354 already points a provider base URL at a mock service
(SHOP_PAYMENT_STRIPE_API_BASE=http://stripe-mock:12111), so the same pattern applied to MoMo would confirm every
refund.
Recommended fix: require the request-bound fields for a confirmation — treat a resultCode 0 response without a
matching orderId (and amount) as MOMO_REFUND_RESPONSE_MISMATCH, and treat a missing signature as unverified rather
than valid.

### MEDIUM

**F2 — The VNPay hash uses form-encoding, not the official sample's encoder; it diverges on ! ' ( ) ~.**
File: payment-service/src/main/java/com/shop/paymentservice/provider/VNPayProvider.java:193-214 (encodedParams and
sign), :180-191 (query); and the same encoder in
payment-service/src/main/java/com/shop/paymentservice/webhook/VNPayCallbackVerifier.java:30-37.
Trigger: any vnp_* parameter whose value contains ! ' ( ) or ~ — for example parentheses in vnp_OrderInfo
("Thanh toán (VND)") or a tilde or apostrophe in the return/notify URL path.
Impact: the canonical string differs from the one the provider computes, so vnp_SecureHash is wrong. The PSP
refuses the payment URL, and/or the IPN hash does not verify, so the callback is rejected and the payment stays
PENDING forever with the order gate closed. Fail-closed, but a silent loss of payments in the money path, and it
sits exactly in the area commit ef9f3c1c claims to fix ("correct the ... VNPay hash").
Evidence: same input, two encoders — Java URLEncoder gives a%21b%27c%28d%29e%7Ef+g, the official Node.js sample
convention (encodeURIComponent with %20 mapped to +) gives a!b'c(d)e~f+g. Spaces and non-ASCII agree; only these
five characters differ.
Recommended fix: encode with the official convention (encodeURIComponent semantics, i.e. do not percent-encode
! ' ( ) ~), and pin it with a test that contains those characters.

**F3 — A single non-success PSP notification permanently bricks the payment.**
File: payment-service/src/main/java/com/shop/paymentservice/webhook/PspCallbackController.java:104 (every VNPay
status other than 00 becomes FAILED) and :87 (every MoMo resultCode other than 0 becomes FAILED); the state machine
at payment-service/src/main/java/com/shop/paymentservice/service/PaymentStateMachine.java:12-16 gives FAILED no
outgoing transition.
Trigger: a VNPay IPN carrying vnp_TransactionStatus 01 (Pending), 03 (Processing) or 99, or a MoMo notification
carrying resultCode 9000 — which the MoMo documentation defines as "transaction is authorized successfully", not a
failure.
Impact: the payment moves PENDING to FAILED. A later success notification then throws PAYMENT_INVALID_STATE
(WebhookEventService.java:167), the event is marked FAILED_RETRYABLE and retried to exhaustion, and the payment
never reaches CAPTURED — the order can never be confirmed even though the customer paid.
Recommended fix: map the non-terminal statuses to a no-op or an INDETERMINATE carrier status instead of FAILED, and
do not treat resultCode 9000 as a failure.

**F4 — The MoMo callback verification assumes the provider's signed field set equals the field set present in the body.**
File: payment-service/src/main/java/com/shop/paymentservice/webhook/MoMoCallbackVerifier.java:15-28 (the field
list) and :44-59 (fields absent from the body are skipped).
Trigger: a MoMo notification whose signed field set differs from the fields present in the body.
Impact: the canonical strings diverge and a legitimate callback is rejected (WEBHOOK_SIGNATURE_INVALID), so the
payment stays PENDING and the order gate never opens. Fail-closed — no false confirmation — but a lost payment.
Evidence: probes — the 13-field v3 body verifies true; the 8-field variant verifies true; the 8-field variant plus
unsigned extras (amount, transId) verifies FALSE; a body missing a signed field verifies FALSE.
Note: the official MoMo v3 IPN example body carries exactly the twelve fields the verifier expects, which matches
the 13-field canonical string, so the current expectation is right for v3. The documentation also describes a
smaller variant; both cannot be satisfied by one rule. Sandbox evidence is required to settle which one the live
account emits (see section 8).

**F5 — The refund signature branch verifies the request field set, not the response field set.**
File: payment-service/src/main/java/com/shop/paymentservice/provider/MoMoProvider.java:176-192.
Trigger: a refund response that does carry a signature field.
Impact: the recomputed canonical string uses the request set (with the empty description and without message and
resultCode), so a response signed over the documented response set never verifies. Probe: outcome=INDETERMINATE
eventId=MOMO_REFUND_SIGNATURE_INVALID. Harmless today — the documented refund response carries no signature, so the
branch is skipped — but the code asserts a verification it cannot perform, and if MoMo ever starts signing the
refund response every refund would fail closed.

**F6 — Both production deployment paths now refuse to start the payment service.**
File: k8s/microservices.yaml:84-85 (production mode on) and docker-compose.prod.yml:66 (production mode on),
against payment-service/src/main/resources/application.yml:36 and docker-compose.yml:352 (the provider defaults to
mock), with PaymentProviderProductionGuard.java:39-42 rejecting mock in production mode.
Trigger: deploying k8s or the prod compose stack as committed, with no operator-supplied SHOP_PAYMENT_PROVIDER.
Impact: the payment-service crashes on startup (k8s pod crash-loop) until an operator provisions an integrated
provider and its credentials. k8s/secrets.template.yaml provisions nothing payment-related. This is the prior
review's D8 (compose) extended to Kubernetes by this batch; it is fail-closed and arguably intended, but the
committed manifests cannot start the service and no operator note says so.

**F7 — The committed env example points operators at a notify URL that does not exist and carries a stale note.**
File: .env.example:189 — SHOP_PAYMENT_VNPAY_NOTIFY_URL=https://<host>/api/v1/webhooks/payments/vnpay/return/ipnnpay/ipn
(the real mapping is PspCallbackController.java:62, /api/v1/webhooks/payments/vnpay/ipn).
Trigger: an operator copying the example.
Impact: the PSP posts the notification to a URL that 404s, the callback is lost and the payment stays PENDING
forever. Two further inconsistencies in the same block: .env.example:171-173 still states that the PSP callback
endpoints are not exposed yet and to keep the flag off until PAY-30 lands — stale since commit 167c9909, which
added them; and .env.example:179 and :188 point the return URLs at /momo/return and /vnpay/return, for which no
endpoint exists (a repo-wide grep finds no such mapping), so the customer's browser redirect 404s after paying.

### LOW

**F8 — Test gaps that let F2, F4 and the callback field set through.**
- payment-service/src/test/java/com/shop/paymentservice/webhook/PspCallbackControllerTest.java:157-170 — the MoMo
  canonical string is built from MoMoCallbackVerifier.SIGNED_FIELDS, the implementation's own constant, so the
  field set, its order and the skip rule are unpinned.
- payment-service/src/test/java/com/shop/paymentservice/provider/VNPayProviderTest.java:168-191 and
  .../webhook/PspCallbackControllerTest.java:195-212 — two copies of the same encode-and-sort algorithm, with no
  published vector; a shared misreading passes both.
- .../provider/VNPayProviderTest.java:114-130 — the refund form body's vnp_SecureHash is never asserted.
- No test covers a space, a non-ASCII character, or any of ! ' ( ) ~ in a VNPay value.
- No end-to-end integration test for the PSP callback path (compare StripeWebhookIT.java).

**F9 — Prior D8, D9 and D10 are unchanged.**
- D8 (production mode forced on while the default provider is mock) — see F6.
- D9 — VNPayProvider.java:76 still sends the full 32-character de-hyphenated UUID as vnp_TxnRef; the documented
  25-character limit could not be confirmed from an accessible official source. Unverified, not confirmed.
- D10 — VNPayProvider.java:121-134 still sends vnp_TransactionDate = now and never sends vnp_TransactionNo. A real
  refund normally needs the original transaction's date and number. Unverified against the live PSP.

---

## 6. Defect matrix for the previous QA_FAIL

| Defect | Status | Evidence |
| --- | --- | --- |
| D1 — MoMo canonicalisation omitted the documented empty fields | **CLOSED** | MoMoProvider.java:73-83 (extraData participates), :127-134 (description), :248-257 (empty payUrl); independent Python recomputation is byte-identical to the Java signature |
| D2 — MoMo refund confirms on an unverified response | **NOT CLOSED (narrowed)** | F1: a body of {"resultCode":0} still yields CONFIRMED (probe). The binding checks at MoMoProvider.java:167-192 help but are optional |
| D3 — No PSP callback path; payments never left PENDING | **CLOSED** | PspCallbackController.java:52-108, MoMoCallbackVerifier.java, VNPayCallbackVerifier.java; the carrier drives WebhookEventService.java:148-183 to CAPTURED and the outbox event payment.captured.v1 reaches the order gate via PaymentLifecycleConsumer.java:30-43 and OrderServiceImpl.java:287-298; the MoMo transId is stored at WebhookEventService.java:164-166 |
| D4 — A genuine MoMo refusal surfaced as INDETERMINATE | **CLOSED** | MoMoProvider.java:199-205; probe: outcome=REJECTED eventId=MOMO_RESULT_CODE_1002 |
| D5 — The guard covered only the default provider | **CLOSED** | Provider beans exist only when fully configured (MoMoProvider.java:29, VNPayProvider.java:33, MockProvider.java:10, StripeProvider.java:19); PaymentProviderRegistrationTest.java:44-122 |
| D6 — The Kubernetes deployment never enabled production mode | **CLOSED** | k8s/microservices.yaml:84-85 (see F6 for the deployment consequence) |
| D7 — "Configured" did not require the callback urls | **CLOSED** | PaymentMoMoProperties.java:27-30, PaymentVNPayProperties.java:37-40, and the two conditions |

Open from the previous review: D8 (see F6), D9 and D10 (see F9) — all three were LOW and remain unverified against
the live PSP.

---

## 7. Verdict

**QA_FAIL**

Reasoning, tied to the acceptance criteria:

- PAY-15 criterion 1 ("zero-decimal currencies are not multiplied") is satisfied in substance, on the same reasoning
  as the first review: a currency the PSP does not support is refused rather than mis-scaled, and VND x 100 on the
  wire is the PSP's own convention.
- PAY-15 criterion 2 ("a provider that is not integrated is not selectable in production") is now satisfied. The
  provider beans exist only when fully integrated, the guard validates every provider name, and it is reachable in
  the compose prod overlay and in Kubernetes. D5, D6 and D7 are closed.
- PAY-30 criteria 2, 3, 5 and 6 are satisfied by the code and by the probes (hash over the decoded parameters, the
  carrier-to-CAPTURED-to-outbox-to-order-gate chain, the de-duplication key and its unique index, the amount
  rejection). Criterion 1 is satisfied for the documented MoMo v3 field set but depends on an assumption the
  documentation does not guarantee (F4), and criterion 4 holds by parity with the Stripe path: both are public and
  both are covered by the gateway's global rate limiter (GlobalRateLimitFilter.java, enabled by default at
  gateway-service/src/main/resources/application.yml:73-77, fail-closed), but neither the Stripe path nor the new
  PSP path imposes an explicit body size limit — that is a parity statement, not a satisfied "size limited".
- The verdict is nevertheless QA_FAIL because the batch does not close the money-path defect the previous review
  raised as HIGH (D2, now F1 — a refund can still be recorded as confirmed on a body with no evidence of belonging
  to the request), and because the commit that claims to correct the VNPay hash does not reproduce the official
  sample's encoder (F2), which can silently lose payments for any value containing ! ' ( ) ~. F3 and F7 are
  additional money-path loss routes. The unit suite is green, but in the callback area the new tests assert the
  implementation against itself (F8), so 257 green tests are not evidence that the PSP contract is honoured.
- The MoMo create/refund signature work, the refusal outcome and the callback path are genuinely good and should be
  kept.

---

## 8. Evidence still required to move to QA_PASS

1. F1 — a MoMo refund test proving that a response without a matching orderId and amount never yields CONFIRMED
   (an absent signature included), and a decision on whether a resultCode 0 response with no binding fields is a
   confirmation or an unknown outcome.
2. F2 — a VNPay test vector over the official sample's encoding that contains ! ' ( ) ~ and a space, plus a
   sandbox round trip proving the PSP accepts the generated vnp_SecureHash for such a value.
3. F3 — a decision on the mapping of the VNPay statuses 01, 03 and 99 and of the MoMo resultCode 9000, with a test
   showing that a non-terminal status does not move the payment to the terminal FAILED state.
4. F4 — sandbox evidence for the exact MoMo IPN field set of the live account: the raw notification body plus the
   provider's own signature, so the verifier's field list and skip rule can be pinned. Until then this item stays
   open and I do not guess which documented variant applies.
5. F6 — an operator note (or manifest change) stating that the k8s and prod compose stacks now require
   SHOP_PAYMENT_PROVIDER plus the provider credentials, or a manifest that provisions an integrated provider.
6. F7 — .env.example:171-173, :179, :188 and :189 corrected so the notify and return URLs match real mappings, and
   the stale "until PAY-30 lands" note removed.
7. F8 — the MoMo callback test building its canonical string from the documented field list rather than from
   MoMoCallbackVerifier.SIGNED_FIELDS; the VNPay refund form body hash asserted; an end-to-end IT for the PSP
   callback to CAPTURED to outbox to order gate.
8. D9 and D10 — the official VNPay documentation for the vnp_TxnRef length limit and for the refund's
   vnp_TransactionDate and vnp_TransactionNo.

---

## 9. Not verified in this review (environment and access limits)

- Live PSP behaviour. No MoMo or VNPay sandbox credentials were available, so every PSP-side statement above is
  derived from the vendor contract in the briefing, from the official MoMo v3 IPN example recovered through
  web_search, and from the official VNPay Node.js sample helper as reproduced by an independent third-party
  repository. developers.momo.vn returned 403 to direct fetches and the official vnpay.github.io sample paths
  returned 404 through three routes, so neither vendor page was read directly.
- The MoMo notification field set (F4). The v3 IPN body recovered through search carries exactly the twelve fields
  the verifier expects, which is consistent with the 13-field canonical string, but the smaller documented variant
  could not be read from the vendor page. This is explicitly left to sandbox evidence rather than guessed.
- The VNPay 25-character vnp_TxnRef limit (D9) and the refund's required transaction date and number (D10) could
  not be confirmed from an accessible official source.
- The end-to-end order checkout with a real PSP was not exercised: no PSP sandbox and no running docker stack in
  this run. The order-side gate was verified by reading the code, by the pre-existing order-service tests, and by
  the payment-service outbox assertions.
- The D1 and D4 conclusions rest on my own independent recomputation of the canonical strings, not on a live PSP
  acceptance.

---

## 10. Citation basis

Every file:line reference in this review points at the **committed** state reviewed (branch audit/production-hardening-run-20261118-a2b6e6fc, HEAD cac13c9f, the three commits ending at 167c9909), not at the working tree. During this review the working tree received unrelated concurrent edits from other agents (an observability profile, an OTLP traces-endpoint change, audit-harness changes); none of them touch the payment-service, and all citations above were re-verified against the committed blobs with git show.

---

## 11. Change hygiene

No project source file was modified, nothing was committed, and no scratch artefact was left outside /tmp. The
only file written by this review is this document.

