# PAY-15 independent review — MoMo / VNPay PSP integration

**Task id:** PAY-15 — "VNPay multiplies the amount by 100 unconditionally and neither provider ever contacts the PSP"
**Severity / area stated in the ledger:** MEDIUM / payment-service
**Reviewer:** Verifier (independent QA gate), run run-20261118-a2b6e6fc
**Date:** 2026-11-19
**Branch:** audit/production-hardening-run-20261118-a2b6e6fc
**Code reviewed:** commit 33976ab9 "fix(payment): call the real MoMo and VNPay providers instead of faking a confirmation"
**Verdict: QA_FAIL**

Note on review conditions: the change was uncommitted when the review started (git diff HEAD showed the work in
the tree) and was committed as 33976ab9 at 14:30:37 while the review was running; the provider files were also
edited at 14:21-14:22 (integral-amount guards were added). Everything below was re-read from disk and re-tested
against the committed state of 33976ab9. The working tree at the end of the review contained only
production-audit/reports/status.json, status.md and state/tasks.json modifications (audit bookkeeping).

---

## 1. Acceptance criteria under review (restated from production-audit/state/tasks.json, PAY-15)

1. "Zero-decimal currencies are not multiplied"
2. "A provider that is not integrated is not selectable in production"

The ledger's own technical_risk text for PAY-15 reads: "VNPay multiplies the amount by 100 regardless of the
currency while VNPay is a zero-decimal currency, and MoMo truncates with longValue. Neither provider contacts the
external PSP and the redirect URL is discarded."

---

## 2. What I read and what I executed

### 2.1 Files read in full

payment-service
- src/main/java/com/shop/paymentservice/provider/MoMoProvider.java (238 lines)
- src/main/java/com/shop/paymentservice/provider/VNPayProvider.java (201 lines)
- src/main/java/com/shop/paymentservice/provider/PaymentProvider.java, PaymentProviderConfig.java,
  PaymentProviderFactory.java, MockProvider.java, StripeProvider.java, StripeCurrencyUnits.java
- src/main/java/com/shop/paymentservice/config/PaymentMoMoProperties.java, PaymentVNPayProperties.java,
  PaymentPspRestClientConfig.java, PaymentProviderProductionGuard.java, PaymentStripeProperties.java
- src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java
- src/main/java/com/shop/paymentservice/service/WebhookEventService.java, PaymentStateMachine.java
- src/main/java/com/shop/paymentservice/webhook/PaymentWebhookController.java, WebhookSignatureVerifier.java,
  WebhookPayload.java
- src/main/java/com/shop/paymentservice/controller/PaymentController.java
- src/main/java/com/shop/paymentservice/dto/CreatePaymentRequest.java, PaymentResponse.java
- src/test/java/com/shop/paymentservice/provider/MoMoProviderTest.java, VNPayProviderTest.java
- src/test/java/com/shop/paymentservice/config/PaymentProviderProductionGuardTest.java
- src/test/java/com/shop/paymentservice/PaymentCaptureConfirmationIT.java
- src/main/resources/application.yml

Deployment / config
- .env.example, docker-compose.yml, docker-compose.prod.yml, k8s/microservices.yaml, k8s/secrets.template.yaml

Other services
- order-service: OrderCreateSaga.java, OrderServiceImpl.java, PaymentServiceClient.java, PaymentGateValidator.java
- utils/common-core: ApiPaths.java (WEBHOOK_PAYMENTS)
- utils/common-spring/pom.xml (httpclient5 dependency backing the new RestClient factories)

### 2.2 Commands executed and their results

| Command | Result |
| --- | --- |
| git status --short / git diff HEAD --stat | Initially: 9 modified + 5 untracked payment-service files. At the end of the review the work had been committed as 33976ab9; only production-audit/reports/* and state/tasks.json remained modified. |
| ./mvnw -B -ntp -pl payment-service test (re-run against commit 33976ab9) | BUILD SUCCESS — "Tests run: 242, Failures: 0, Errors: 0, Skipped: 0", 54.0 s |
| ./mvnw -T1C -pl payment-service validate | BUILD SUCCESS — "You have 0 Checkstyle violations." |
| /tmp/pay15/SignProbe.java (scratch program outside the repo: exact copy of MoMoProvider.sign() plus the documented canonicalisation) | 4/4 signature comparisons MISMATCH — see D1/D2/D4 |
| web_fetch developers.momo.vn and sandbox.vnpayment.vn | Blocked in this environment (403 / redirect page). Vendor formats were therefore taken from the MoMo official documentation surfaced through web_search and from the vendor contract supplied in the task briefing. |

---

## 3. Answers to the review questions

### Q1 — Does MoMoProvider contact the PSP, with the documented signature and a verified response signature?

**It contacts the PSP; the request signature canonicalisation is wrong; the refund response signature is not verified.**

What is right:
- create is POST {baseUrl}/v2/gateway/api/create (MoMoProvider.java:31, :95); refund is POST
  {baseUrl}/v2/gateway/api/refund (:32, :147). Both are real HTTP calls through a pooled, time-bounded RestClient
  (PaymentPspRestClientConfig.java, 30 s connect/response timeout, matching MoMo's "minimum timeout 30s" note).
- The create body matches the documented body: partnerCode, requestId, orderId, amount, orderInfo, redirectUrl,
  ipnUrl, requestType=captureWallet, extraData, lang, signature (MoMoProvider.java:84-93).
- The refund body matches the documented body, and the refund orderId ("RF" + de-dashed uuid,
  MoMoProvider.java:126) differs from the purchase orderId, as MoMo requires.
- The capture response signature IS verified over exactly the nine documented fields
  (MoMoProvider.java:208-224, called at :164: accessKey, amount, message, orderId, partnerCode, payUrl,
  requestId, responseTime, resultCode) and matches the briefing contract.
- Amounts are now integral-VND only: a fractional amount is refused instead of rounded
  (MoMoProvider.java:61-63), and the signed amount text is the same text that is transmitted (:67-68 vs :87).

What is wrong: the canonicalisation is not the documented rule. The implementation's signer skips empty values
(MoMoProvider.java:227-238, "if (value == null || value.isBlank()) { return; }"), but the documented rule is a fixed
sorted field list in which every listed key participates, including empty ones.

- create: the field set is accessKey, amount, extraData, ipnUrl, orderId, orderInfo, partnerCode, redirectUrl,
  requestId, requestType. extraData="" is put into the map at MoMoProvider.java:73 and then dropped at :230, so
  the signed string is missing "extraData=".
- refund: the field set is accessKey, amount, description, orderId, partnerCode, requestId, transId.
  description="" is put into the map at :128 and dropped at :230, so "description=" is missing.

MoMo's official documentation states the create signature as: "a String sort all key name of data field from a-z:
accessKey=$accessKey&amount=$amount&extraData=$extraData&ipnUrl=$ipnUrl&orderId=$orderId&orderInfo=$orderInfo&
partnerCode=$partnerCode&redirectUrl=$redirectUrl&requestId=$requestId&requestType=$requestType" — no exclusion of
empty values. The task briefing states the same field set.

Probe output (/tmp/pay15/SignProbe.java, secret "SECRET"):
- create, extraData="": implementation string omits extraData=; impl HMAC 528c634b767ea17d5ee2866cf08f44e79810191f78dd6df09cf292b705f532bc
  vs documented aff86ec475464e9c2e2e898ed2f867cba59e170a5100e26f64a9ff3fea41e0a7 -> MISMATCH.
- refund, description="": impl 24a1ffddc20bf7e6f4a6acad81069eae4f4c614d82b157aef6963e45127ab29e vs documented
  ec9d7fd89d6cbeeb3fe0b9dd00ec15edce6d813b3fbc1fd1dd0bb283ecf028c1 -> MISMATCH.

Impact: the PSP recomputes the signature over the documented string, so every create and every refund request
this code produces carries a signature the PSP will reject. The integration cannot work against the live sandbox
as written.

The refund response signature is not verified at all (MoMoProvider.java:149-156), although MoMo's refund response
carries a signature over accessKey, amount, description, orderId, partnerCode, requestId, transId.

### Q2 — Definitive rejection vs unknown outcome; any path that can still report a false CONFIRMED?

**Capture is sound. Refund has a false-confirmation path, and capture misclassifies definitive rejections.**

Capture (no path returns CONFIRMED — correct):
- unreachable PSP / 5xx -> INDETERMINATE "MOMO_CREATE_UNAVAILABLE" (:95-98)
- unparseable body -> INDETERMINATE "MOMO_MALFORMED_RESPONSE" (:190-193)
- missing or invalid response signature -> INDETERMINATE "MOMO_RESPONSE_SIGNATURE_INVALID" (:164-166)
- resultCode != 0 -> REJECTED "MOMO_RESULT_CODE_n" (:167-169)
- reported amount != requested amount -> REJECTED "MOMO_AMOUNT_MISMATCH" (:171-174)
- missing payUrl -> INDETERMINATE "MOMO_PAY_URL_MISSING" (:176-178)

Defect A — false confirmation on refund (MoMoProvider.java:149-156): the refund trusts resultCode == 0 with no
signature verification and returns ProviderResult.confirmed(transactionId). Any body that parses as JSON and
carries "resultCode":0 — a forged body, a replayed body from another merchant, a proxy error page that happens to
be JSON — marks the refund CONFIRMED. PaymentServiceImpl.refund then sets the payment to REFUNDED and publishes
"payment.refunded.v1" (PaymentServiceImpl.java:171-177). The platform records a refund the PSP never performed.
The unit test MoMoProviderTest.refund_callsTheProviderRefundEndpoint mocks a response containing no signature
field at all and asserts CONFIRMED, i.e. the test locks the defect in.

Defect B — definitive rejection reported as unknown outcome (MoMoProvider.java:164 runs before :167): because the
signer drops an empty payUrl, a genuine MoMo error response (no payUrl field; the documented response format still
contains "payUrl=") fails verification and is returned as INDETERMINATE instead of REJECTED. PaymentServiceImpl
maps INDETERMINATE to ErrorCode.SERVICE_UNAVAILABLE (PaymentServiceImpl.java:135), so every real MoMo refusal
(resultCode 1002, 4005, ...) surfaces to the caller as a retryable 503 instead of a definitive rejection. Probe:
error-response impl b37eb4afc3c566fddffc36ecbcecd8a3e1aa6e90e1c6243c40a7792c58a6c7bb vs documented
6a044b521252c5045338f72eb9647a637f6cb8a3930dfea943df83a98e14030d -> MISMATCH. The test helper
MoMoProviderTest.errorResponse() omits payUrl from the signed string, reproducing the implementation's rule
instead of the vendor's, which hides this.

Dead end — MoMo refund can never succeed in this platform: the capture path stores providerEventId = orderId =
the payment UUID (MoMoProvider.java:179 -> PaymentServiceImpl.awaitClientConfirmation :150-155), but the refund
requires a numeric transId (MoMoProvider.java:112-113 -> REJECTED "MOMO_TRANSACTION_ID_MISSING"). MoMo's transId
only arrives in the IPN, and no MoMo IPN endpoint exists (Q7).

### Q3 — Does VNPayProvider's redirect URL carry a valid vnp_SecureHash, with the correct URL-encoded form?

**Mechanically yes.**

- vnp_SecureHash = HMAC-SHA512(hashSecret) over the alphabetically sorted "k=v" of the non-empty vnp_*
  parameters, excluding vnp_SecureHash and vnp_SecureHashType (VNPayProvider.java:190-199); it is computed over
  the parameter map before the hash itself is inserted (:93).
- The signed set is vnp_Version, vnp_Command, vnp_TmnCode, vnp_Amount, vnp_CurrCode, vnp_OrderInfo,
  vnp_ReturnUrl, vnp_NotifyUrl, vnp_TxnRef, vnp_IpAddr, vnp_Locale, vnp_CreateDate, vnp_ExpireDate and the
  optional vnp_BankCode — the documented set.
- The signature is computed over the unencoded values and the URL carries the encoded values
  (VNPayProvider.java:165-177), which is the documented form.
- VNPayProviderTest.capture_buildsASignedRedirectAndWaitsForTheCustomerToPay recomputes the expected hash with
  its own Mac code from the decoded query and compares — a genuine independent recomputation, not an assertion
  against the implementation.

Residual risks (not confirmed defects — no sandbox credentials in this environment):
- vnp_TxnRef is now the full 32-character de-dashed UUID (VNPayProvider.java:73) where the previous code
  truncated to 16 characters. VNPay's documented limit for vnp_TxnRef is 25 characters; I could not confirm that
  from an accessible official source here (developers.vnpayment.vn fetch returned 403), so this is flagged as
  unverified risk, not a confirmed defect. It is a regression in the sense that the previous code was safe.
- The refund sends vnp_TransactionDate defaulting to "now" (VNPayProvider.java:123, :165-167) and never sends
  vnp_TransactionNo; a real refund normally needs the original transaction's date/number. Unverified against the
  live PSP; flagged as MEDIUM risk.
- The refund response is not signature-verified (VNPayProvider.java:153-162); the stated contract for the
  merchant API only specifies vnp_ResponseCode, so this is acceptable per the contract.

### Q4 — Currency handling; is acceptance criterion 1 satisfied?

**The criterion conflicts with the vendor contract. The criterion is the thing that is wrong; the implementation
is right for a real integration, and the real defect the criterion was aimed at ("regardless of the currency") is
fixed.**

Facts:
- VNPay's wire format is minor units: vnp_Amount is always amount x 100, and VNPay only supports VND. Confirmed
  in this review from vendor material ("vnp_Amount: VND x 100") and from the contract given in the briefing.
- VND is a zero-decimal currency (ISO 4217), and the repository's own unit table lists VND as zero-decimal
  (StripeCurrencyUnits.java:11-13, StripeCurrencyUnits.ZERO_DECIMAL_CURRENCIES), where toMinor(50000,"VND") = 50000.
- Stripe and VNPay therefore disagree by contract: Stripe takes VND in major units, VNPay takes VND x 100.

Consequence: "zero-decimal currencies are not multiplied" cannot hold literally for VNPay/VND — VND is a
zero-decimal currency and it is multiplied, because the PSP demands it. The audit's original technical_risk
conflated "the currency has zero decimals" with "the PSP wire format uses minor units". The genuine defect in the
old code was the unconditional multiplication regardless of currency.

What the implementation now does:
- VNPay capture: non-VND -> REJECTED before any PSP call (VNPayProvider.java:63-65); VND -> x 100 (:80, :169-171).
- MoMo capture: non-VND -> REJECTED (MoMoProvider.java:55-57); VND -> sent as whole VND, no multiplication
  (:67-68, :87). Correct: MoMo's amount is a Long in VND.
- Non-integral amounts are refused on both providers rather than rounded or truncated
  (MoMoProvider.java:61-63, VNPayProvider.java:67-69).
- The x100 lives in a hardcoded pspAmount() (VNPayProvider.java:169-171), not in a currency-unit table. That is
  acceptable only because the VND precondition makes it unreachable for any other currency; it is not a general
  currency-unit rule.

Criterion 1: NOT satisfied literally (VND is zero-decimal and it is multiplied). Satisfied in substance — a
currency the PSP does not support is refused rather than silently mis-scaled, which is what the criterion was
actually protecting against. For a real integration I judge the implementation correct: a VNPay integration that
stopped multiplying VND by 100 would be rejected by the PSP. I recommend the criterion be restated as "the x100 is
applied only where the PSP wire format requires it, and a currency the PSP does not support is refused rather than
mis-scaled" — which this implementation satisfies.

### Q5 — Does the production guard make a simulated or unintegrated provider unselectable? Is it reachable?

**The guard works for the default provider and is reachable through docker-compose.prod.yml, but criterion 2 is
only partially satisfied.**

Mechanics that are right:
- PaymentProviderProductionGuard is a @Configuration with @ConditionalOnProperty("shop.payment.production-mode" =
  true); its @PostConstruct throws IllegalStateException when the default provider is "mock"
  (PaymentProviderProductionGuard.java:39-42) or is not configured (:46-59).
- Wiring is correct: application.yml:37 (production-mode: ${SHOP_PAYMENT_PRODUCTION_MODE:false}),
  docker-compose.prod.yml:66 sets SHOP_PAYMENT_PRODUCTION_MODE "true" on the payment-service service,
  docker-compose.yml:353 defaults it to false elsewhere.
- The mock provider cannot be selected while production mode is on, by any request path: MockProvider is only
  registered when shop.payment.provider=mock (MockProvider.java:10) and with production mode on and provider=mock
  the guard aborts startup, so there is no mock bean to select. A request naming an unregistered provider is
  rejected by PaymentProviderFactory.getProvider.

Gaps:
- (a) The guard validates only the DEFAULT provider. MoMoProvider and VNPayProvider are unconditional @Component
  beans (MoMoProvider.java:26, VNPayProvider.java:31), so in a production deployment with provider=stripe a
  create request with provider="momo" is accepted (PaymentServiceImpl.java:54 -> :62-63) and the payment is bound
  to a provider with no integration; the failure appears only at capture time as INDETERMINATE -> 503
  SERVICE_UNAVAILABLE (MoMoProvider.java:58-60). An unintegrated provider is therefore still selectable in
  production — it fails late and with the wrong error class. No test covers this.
- (b) The guard is not wired into the Kubernetes production manifest: k8s/microservices.yaml:81-86 runs
  payment-service with SPRING_PROFILES_ACTIVE=prod and no SHOP_PAYMENT_PRODUCTION_MODE, so in that deployment
  the guard never activates and shop.payment.provider defaults to mock.
- (c) PaymentMoMoProperties.isConfigured() (:27-29) does not require return-url/notify-url, so a "configured"
  provider can still be unable to confirm anything. Same for VNPay (paymentUrl, apiUrl, tmnCode, hashSecret only).
- (d) Operational consequence of forcing production mode in docker-compose.prod.yml while docker-compose.yml:352
  still defaults SHOP_PAYMENT_PROVIDER to mock: a prod stack started without a real provider env now crash-loops
  payment-service at startup. That is the intended fail-fast, but it is an undocumented deployment-breaking change
  and belongs in the release notes and the .env example.

### Q6 — Are the tests genuine? What is missing?

**Partly genuine; two of the new tests encode the implementation's own deviation instead of the vendor rule.**

Genuine:
- VNPayProviderTest.expectedSignature recomputes the HMAC-SHA512 with its own Mac code from the decoded query and
  compares — an independent recomputation, not an assertion against the implementation.
- MoMoProviderTest.expectedCreateSignature also rebuilds the string with its own Mac code — independent code, but
  it encodes the wrong canonical string (see below).
- PaymentProviderProductionGuardTest boots a real context (ApplicationContextRunner) and asserts both that the
  context fails in production mode and that the guard bean is absent outside production mode.
- The capture tests exercise real HTTP semantics through MockRestServiceServer (method, URL, JSON body, form
  encoding) and cover the unreachable PSP, a bad response signature, a non-VND currency, an amount mismatch and a
  fractional amount.

Not genuine / masking defects:
- MoMoProviderTest.errorResponse() builds the error-response signature without payUrl, i.e. it reproduces the
  implementation's blank-skipping rule rather than the vendor's, so the "definitive rejection" test passes only
  because the production code and the test share the same wrong assumption (Q2 defect B).
- MoMoProviderTest.refund_callsTheProviderRefundEndpoint mocks a response with no signature field and asserts
  CONFIRMED — the false-confirmation path is enshrined by the test (Q2 defect A).
- VNPayProviderTest.expectedSignature derives the signed field set from the parameters the implementation itself
  emitted, so a parameter missing from both the URL and the signature would still pass; the test also decodes the
  URL, so it never verifies the URL carries the encoded form.

Missing cases that would let a regression through:
- a create and a refund signature computed with the documented canonical string (all listed keys, including the
  empty extraData / description);
- a refund response with a wrong or absent signature -> must not be CONFIRMED;
- a MoMo error response whose signature includes payUrl= -> must be REJECTED, not INDETERMINATE;
- a non-VND refund on both providers (only capture is tested);
- a MoMo refund whose providerIntentId is the UUID orderId (the transId dead end), exercised through
  PaymentServiceImpl rather than only at the provider level;
- an unintegrated provider selected by name while production mode is on (per-request provider selection);
- the VNPay redirect: a parameter present in the URL but absent from the signed set, and the encoded form of the
  emitted URL.

### Q7 — Does the change break behaviour the rest of the platform depends on?

**The REQUIRES_CONFIRMATION pattern is the platform's own established pattern — but the other half (the PSP
callback) does not exist, so with MoMo or VNPay selected the payment flow dead-ends and the order can never be
confirmed.**

Consistent parts:
- REQUIRES_CONFIRMATION is already the platform pattern for a redirect provider: StripeProvider.java:150 returns
  requiresConfirmation(intentId, clientSecret); PaymentServiceImpl.java:133/:150-155 keeps the payment PENDING and
  stores clientSecret and providerIntentId; PaymentCaptureConfirmationIT:97-112 pins that semantic (stays PENDING,
  publishes no capture event); PaymentStateMachine allows PENDING -> CAPTURED/FAILED only, so PENDING is legal.
- Nothing downstream assumes a synchronous MoMo/VNPay confirmation: order-service only reads the captured-payment
  snapshot (PaymentServiceClient.findCapturedByOrderId) and triggers refunds (refundByOrderId).

Broken parts:
- The only webhook endpoint is PaymentWebhookController.handle, mapped to POST /api/v1/webhooks/payments/{provider}
  — a single path segment (PaymentWebhookController.java:26-31, ApiPaths.WEBHOOK_PAYMENTS). It understands only
  (a) Stripe, verified through the Stripe SDK, and (b) a generic carrier payload signed as a hex HMAC-SHA256 of the
  raw body via X-Webhook-Signature (WebhookSignatureVerifier.java). MoMo sends an IPN signed over its own sorted
  parameters; VNPay sends a GET whose vnp_SecureHash covers the query parameters. Neither matches, and neither body
  maps onto WebhookPayload (which requires paymentId and status).
- The URLs this change documents in .env.example do not exist: /api/v1/webhooks/payments/momo/return,
  .../momo/ipn, .../vnpay/return, .../vnpay/ipn (.env.example:174-175, 182-183) all 404 against the controller
  mapping. The only working shape is /api/v1/webhooks/payments/{provider} (used by the Postman collection at
  docs/postman/generate_master_collection.js:836).
- Consequence: with MoMo or VNPay selected, capture returns REQUIRES_CONFIRMATION and the payment stays PENDING
  forever. The only way to CAPTURED is a webhook, and no working webhook exists for these providers. The order
  side then blocks: order-service PaymentGateValidator requires a CAPTURED payment before confirming an order
  (PaymentGateValidator.java:21-33, default require-captured-payment=true) and ConfirmOrchestrationIT asserts the
  fail-closed behaviour. So checkout can never complete with these providers. Before this change the fake CONFIRMED
  let the flow proceed; after it, the flow is a dead end.
- The refund side is equally affected: a MoMo refund needs the numeric transId from the IPN that never arrives
  (Q2 dead end), and a VNPay refund needs the original transaction date/number that the platform never records.

---

## 4. Defects by severity

### HIGH

D1 — MoMo request signature canonicalisation omits the documented empty fields.
  File: payment-service/src/main/java/com/shop/paymentservice/provider/MoMoProvider.java:227-238 (blank-skip),
  :73 (extraData), :128 (description); create signature at :93, refund signature at :142.
  Trigger: every MoMo create and every MoMo refund against the live PSP.
  Impact: the PSP recomputes the signature over the documented string and rejects the request; the MoMo
  integration cannot work at all.
  Evidence: probe /tmp/pay15/SignProbe.java — impl 528c634b… vs documented aff86ec4… (create),
  impl 24a1ffdd… vs documented ec9d7fd8… (refund). Vendor format confirmed from the MoMo documentation quoted in
  Q1 and from the contract in the briefing.

D2 — MoMo refund trusts an unverified response and can report a false confirmation.
  File: MoMoProvider.java:149-156 (resultCode check at :152, ProviderResult.confirmed at :156); the existing
  responseSignatureValid method (:208-224) is never called on the refund path.
  Trigger: any refund response body that parses as JSON and carries "resultCode":0.
  Impact: the payment is marked REFUNDED and "payment.refunded.v1" is published (PaymentServiceImpl.java:171-177)
  for a refund the PSP never performed.
  Evidence: MoMoProviderTest.refund_callsTheProviderRefundEndpoint mocks a response with no signature field and
  asserts CONFIRMED.

D3 — No PSP callback path for MoMo or VNPay: payments never leave PENDING and orders can never be confirmed.
  Files: payment-service/src/main/java/com/shop/paymentservice/webhook/PaymentWebhookController.java:26-31
  (only /{provider}, Stripe or generic body-HMAC), WebhookSignatureVerifier.java, .env.example:174-175, 182-183
  (URLs that 404), MoMoProvider.java:179 + PaymentServiceImpl.java:150-155 (providerIntentId is the UUID orderId,
  so the MoMo refund transId can never be numeric).
  Trigger: selecting momo or vnpay as the provider.
  Impact: capture returns REQUIRES_CONFIRMATION, the payment stays PENDING forever, the order-side captured-payment
  gate (order-service PaymentGateValidator.java:21-33) never opens and checkout cannot complete; MoMo refunds
  always fail with MOMO_TRANSACTION_ID_MISSING.
  Evidence: a repo-wide grep for momo|vnpay under payment-service/src/main returns only the providers, the
  properties records, the guard and the RestClient config — no controller, no service, no payload mapper.

### MEDIUM

D4 — A definitive MoMo rejection is reported as an unknown outcome (INDETERMINATE -> 503).
  File: MoMoProvider.java:164 (signature check) runs before :167 (resultCode check); the blank payUrl is dropped by
  sign() at :230, so a real MoMo error response fails verification.
  Trigger: any MoMo error response (resultCode != 0), which never carries a payUrl.
  Impact: PaymentServiceImpl.java:135 turns it into SERVICE_UNAVAILABLE, so refusals are retried as if transient.
  Evidence: probe — error-response impl b37eb4af… vs documented 6a044b52…; masked by
  MoMoProviderTest.errorResponse(), which omits payUrl from the signed string.

D5 — The production guard covers only the default provider; an unintegrated provider is still selectable by name.
  Files: PaymentProviderProductionGuard.java:33-59 (default provider only); MoMoProvider.java:26 and
  VNPayProvider.java:31 (unconditional @Component beans); PaymentServiceImpl.java:54, :62-63
  (per-request provider selection through PaymentProviderFactory.getProvider).
  Trigger: production deployment with provider=stripe and a create request carrying provider="momo".
  Impact: the payment is bound to a provider with no integration and fails at capture with a 503 instead of being
  refused at selection time. No test covers this.

D6 — The guard is not active in the Kubernetes production deployment.
  Files: k8s/microservices.yaml:81-86 (SPRING_PROFILES_ACTIVE=prod, no SHOP_PAYMENT_PRODUCTION_MODE);
  docker-compose.prod.yml:66 is the only place that sets it.
  Trigger: a k8s-based production deployment.
  Impact: shop.payment.provider defaults to mock there and the mock provider stays selectable — criterion 2 is not
  met on that deployment path.

D7 — The guard's "configured" test is weaker than a usable integration.
  File: PaymentMoMoProperties.java:27-29 (baseUrl, partnerCode, accessKey, secretKey only — return-url and
  notify-url are not required); PaymentVNPayProperties.java:27-29 likewise.
  Trigger: production mode with momo configured without the callback URLs.
  Impact: the guard passes while the provider can never confirm a payment (and the signature omits the empty
  ipnUrl/redirectUrl, see D1).

### LOW

D8 — docker-compose.prod.yml forces production mode on while the base compose still defaults the provider to mock
  (docker-compose.prod.yml:66 vs docker-compose.yml:352). A prod stack without a provisioned provider now
  crash-loops payment-service. Fail-fast is right, but this is an undocumented breaking change for operators.

D9 — VNPay vnp_TxnRef is the full 32-character de-dashed UUID (VNPayProvider.java:73) where the previous code
  truncated to 16. If VNPay's documented 25-character limit is enforced, the payment page will refuse the
  transaction. Unverified here (official doc fetch blocked) — listed as a risk, not a confirmed defect.

D10 — VNPay refund sends vnp_TransactionDate = now and never sends vnp_TransactionNo
  (VNPayProvider.java:123, :165-167). A real refund normally needs the original transaction's date/number.
  Unverified against the live PSP.

---

## 5. What is correct and worth keeping

- The MoMo and VNPay providers now contact the PSP instead of fabricating a confirmation; the fake
  "momo_<uuid>" / "vnp_rf_<8 chars>" identifiers are gone.
- Capture never returns CONFIRMED, and unreachable / unparseable / unverifiable answers are INDETERMINATE.
- The VNPay redirect hash is computed over the right field set and the right (unencoded vs encoded) form, and the
  VNPay test recomputes it independently.
- Non-VND currencies are refused before any PSP call on both providers, and fractional amounts are refused rather
  than rounded or truncated.
- The production guard fails startup on a simulated or unconfigured default provider, and the property is wired
  through application.yml and docker-compose.prod.yml.
- 242 payment-service tests pass and checkstyle reports 0 violations on the committed state.

---

## 6. Verdict

**QA_FAIL**

Reasoning: criterion 1 is satisfied in substance but not literally (the literal wording contradicts the VNPay
wire contract; the implementation follows the contract and I judge it correct — see Q4). Criterion 2 is only
partially satisfied (D5, D6, D7). More decisively, the change introduces a real production regression (D3) and
leaves the MoMo integration non-functional against the live PSP (D1, D2, D4). The unit suite is green because the
new tests encode the implementation's own deviations (Q6), so "242 tests green" is not evidence of correctness
here.

### Evidence required to move to QA_PASS

1. A MoMo create and refund request signature recomputed over the documented canonical string — every listed key
   participating, including the empty extraData and description — plus a unit test that builds the expected
   signature from the documented field list rather than from the implementation's rule.
2. A MoMo refund that verifies the response signature over accessKey, amount, description, orderId, partnerCode,
   requestId, transId, and a test proving a response with a wrong or absent signature never yields CONFIRMED.
3. The resultCode check evaluated before the signature check for MoMo error responses (or an error-path signature
   rule that includes the empty payUrl), with a test showing a real MoMo error response is REJECTED, not
   INDETERMINATE.
4. A working PSP callback for MoMo and VNPay (an endpoint that verifies the MoMo parameter signature / the VNPay
   vnp_SecureHash over the query parameters and maps them onto WebhookPayload), or a documented decision that these
   providers are not selectable until such a path exists. The .env.example callback URLs must match a real mapping.
5. A decision on how the MoMo transId reaches the platform (capture response or IPN) so that refund stops failing
   with MOMO_TRANSACTION_ID_MISSING, with a test at the PaymentServiceImpl level.
6. The production guard applied to every selectable provider, not only the default one, or per-request provider
   selection restricted to providers that are configured in production mode — with a test.
7. The guard wired into the Kubernetes production manifest, or the k8s path explicitly excluded from the
   acceptance criterion with the operator impact documented.
8. A decision on the VNPay vnp_TxnRef length and the refund's vnp_TransactionDate / vnp_TransactionNo, evidenced
   against the official VNPay documentation or the sandbox.

### Not verified in this review (environment limits)

- Live PSP behaviour: no MoMo or VNPay sandbox credentials were available, so every PSP-side statement above is
  derived from the vendor contract in the briefing plus the vendor documentation retrieved via search. The MoMo
  request/response canonical strings were corroborated from developers.momo.vn; the official MoMo and VNPay doc
  pages could not be fetched directly (403 / redirect).
- The VNPay 25-character vnp_TxnRef limit (D9) could not be confirmed from an accessible official source.
- End-to-end order checkout with a real PSP was not exercised (no PSP sandbox, no docker stack in this run).

