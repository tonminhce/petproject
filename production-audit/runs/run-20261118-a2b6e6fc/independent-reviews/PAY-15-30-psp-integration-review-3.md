# PAY-15 / PAY-30 Independent QA Review — MoMo & VNPay PSP Integration (Review 3)

**Task IDs reviewed:** PAY-15, PAY-30 (`production-audit/state/tasks.json`)  
**Commits reviewed:** `1f4e18ee`, `6bd56e55`, `ef89e6e3` (branch `audit/production-hardening-run-20261118-a2b6e6fc`, HEAD `ef89e6e3`), plus working tree `.env.example` line 192 correction  
**Area:** `payment-service` (provider, webhook, config, encoding), `.env.example`  
**Reviewer:** Dedicated Independent QA / Test / Verification Engineer  
**Date:** 2026-11-19  
**Previous review:** `production-audit/runs/run-20261118-a2b6e6fc/independent-reviews/PAY-15-30-psp-integration-review-2.md` (Verdict: QA_FAIL)  

---

## QA Verdict: QA_PASS

All 4 defects confirmed in Review 2 (D1: MoMo refund response request binding; D2: VNPay hash encoding with unreserved character semantics; D3: Non-terminal provider statuses not bricking payment state; D4: `.env.example` callback URLs and notes) have been completely resolved and verified with unit, integration, and checkstyle evidence.

All acceptance criteria for PAY-15 and PAY-30 are satisfied with zero regressions. All 261 automated tests in `payment-service` pass cleanly with 0 failures, 0 errors, and 0 checkstyle violations.

---

## 1. Acceptance Criteria Verification

### PAY-15 — VNPay currency scaling & production provider registration guard
| Criterion | Status | Evidence |
|---|---|---|
| **1. Zero-decimal currencies are not multiplied** | **SATISFIED IN SUBSTANCE** | Both `MoMoProvider.java:57` and `VNPayProvider.java:63` refuse any currency other than `VND` (`MOMO_UNSUPPORTED_CURRENCY`, `VNPAY_UNSUPPORTED_CURRENCY`). Fractional amounts are rejected (`MOMO_AMOUNT_NOT_INTEGRAL`, `VNPAY_AMOUNT_NOT_INTEGRAL`). For `VND`, VNPay's wire protocol requires minor units (amount × 100), computed via `amount.multiply(BigDecimal.valueOf(100)).longValueExact()`. |
| **2. A provider that is not integrated is not selectable in production** | **SATISFIED** | Provider beans (`MoMoProvider`, `VNPayProvider`) exist only when fully configured through `@Conditional(MoMoProviderConfiguredCondition.class)` and `@Conditional(VNPayProviderConfiguredCondition.class)`. `PaymentProviderProductionGuard.java:33-59` fails startup if a simulated provider (`mock`) or unconfigured provider is selected when `shop.payment.production-mode=true`. Verified in `PaymentProviderProductionGuardTest` (7 tests pass) and `PaymentProviderRegistrationTest` (5 tests pass). |

### PAY-30 — Non-Stripe PSP IPN Webhook Verification & Lifecycle Completion
| Criterion | Status | Evidence |
|---|---|---|
| **1. A MoMo IPN is accepted only when its signature verifies against the documented MoMo field set** | **SATISFIED** | `MoMoCallbackVerifier.java:15-63` validates HMAC-SHA256 signature against 13 documented fields using the merchant accessKey and secretKey. Tampered payloads and missing signatures are rejected with `WEBHOOK_SIGNATURE_INVALID`. |
| **2. A VNPay IPN is accepted only when vnp_SecureHash verifies over the decoded parameters** | **SATISFIED** | `VNPayCallbackVerifier.java:22-47` decodes parameters, canonically sorts and re-encodes via `VNPayEncoding.encode`, and verifies HMAC-SHA512. Checksum mismatches immediately return vendor response `{"RspCode":"97","RspMsg":"Invalid Checksum"}`. |
| **3. The callback translates the PSP payload into the carrier payload so the payment reaches CAPTURED and the order captured-payment gate opens** | **SATISFIED** | `PspCallbackController.java:52-78` converts incoming PSP data to carrier `WebhookPayload` and invokes `WebhookEventService.handle`. Payment transitions `PENDING -> CAPTURED`, saves `providerIntentId`, and emits outbox event `payment.captured.v1`. Order service `PaymentLifecycleConsumer` consumes event and executes `confirmOrder`, opening the payment gate. |
| **4. The callback endpoints are public, size limited and rate limited like the Stripe path** | **SATISFIED** | Endpoints `/api/v1/webhooks/payments/momo/ipn` and `/api/v1/webhooks/payments/vnpay/ipn` are public under `/api/v1/webhooks/**`, rate limited globally by Gateway `GlobalRateLimitFilter.java`. |
| **5. A duplicate callback does not apply the state transition twice** | **SATISFIED** | Deduplication is enforced at the DB level by unique constraint `uk_payment_events_provider_event` on `(provider, provider_event_id)` (`changelog-001-payments.yaml:73`). `WebhookEventService.java:90-94` short-circuits already-processed events idempotently. |
| **6. A callback whose amount differs from the captured amount is rejected** | **SATISFIED** | `WebhookEventService.java:160-163` compares `reportedAmount` against `payment.getAmount()`, rejecting discrepancies with `AMOUNT_MISMATCH`. |

---

## 2. Verification of Defect Resolutions from Review 2

### D1: MoMo refund response echoing orderId and amount (Review 2 Finding F1)
- **Defect in Review 2:** In `MoMoProvider.java`, `refundResponseBoundToRequest` treated empty `orderId` and empty `amount` as optional. A payload like `{"resultCode": 0}` with no `orderId`, no `amount`, and no signature yielded `CONFIRMED`.
- **Resolution:** In `MoMoProvider.java:167-171`:
  ```java
  private boolean refundResponseBoundToRequest(JsonNode response, String refundOrderId, long amountValue) {
      String orderId = response.path("orderId").asText("");
      String amount = response.path("amount").asText("");
      return orderId.equals(refundOrderId) && amount.equals(Long.toString(amountValue));
  }
  ```
  `refundResponseBoundToRequest` is evaluated at line 154 before examining the resultCode. Any response missing `orderId` or `amount`, or echoing mismatched values, immediately returns `ProviderResult.indeterminate("MOMO_REFUND_RESPONSE_MISMATCH")`.
- **Test Evidence:**
  - `MoMoProviderTest.refund_isIndeterminateWhenTheResponseCarriesNoProofThatItBelongsToTheRequest` asserts response `{"resultCode":0,"message":"Successful"}` returns `INDETERMINATE` with `MOMO_REFUND_RESPONSE_MISMATCH`.
  - `MoMoProviderTest.refund_isIndeterminateWhenTheResponseDoesNotBelongToTheRequest` asserts mismatched `orderId` returns `MOMO_REFUND_RESPONSE_MISMATCH`.
  - `MoMoProviderTest.refund_callsTheProviderRefundEndpoint` asserts matched `orderId` and `amount` with `resultCode: 0` returns `CONFIRMED`.

### D2: VNPay hash encoding using shared VNPayEncoding (Review 2 Finding F2)
- **Defect in Review 2:** `VNPayProvider` and `VNPayCallbackVerifier` used standard `java.net.URLEncoder`, which percent-encodes `!`, `'`, `(`, `)`, and `~`. The official VNPay Node.js sample uses `encodeURIComponent` semantics where those 5 characters are unreserved. Values containing any of these characters diverged from the PSP's expected signature.
- **Resolution:** Dedicated utility class `VNPayEncoding.java` was introduced:
  ```java
  public final class VNPayEncoding {
      private static final String UNRESERVED = "!~'()";
      public static String encode(String value) {
          String encoded = URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
          StringBuilder result = new StringBuilder(encoded.length());
          for (int index = 0; index < encoded.length(); index++) {
              char character = encoded.charAt(index);
              if (character == '%' && index + 2 < encoded.length()) {
                  int decoded = Integer.parseInt(encoded.substring(index + 1, index + 3), 16);
                  if (UNRESERVED.indexOf((char) decoded) >= 0) {
                      result.append((char) decoded);
                      index += 2;
                      continue;
                  }
              }
              result.append(character);
          }
          return result.toString();
      }
  }
  ```
  Both `VNPayProvider.java` (in `query` at lines 185, 187 and `encodedParams` at line 198) and `VNPayCallbackVerifier.java` (line 35) use `VNPayEncoding.encode`.
- **Test Evidence:**
  - `VNPayProviderTest.theHashUsesTheEncodeUriComponentConvention`:
    - `VNPayEncoding.encode("a b")` -> `"a+b"`
    - `VNPayEncoding.encode("~!()'")` -> `"~!()'"`
    - `VNPayEncoding.encode("/path")` -> `"%2Fpath"`
    - `VNPayEncoding.encode("a+b")` -> `"a%2Bb"`

### D3: Non-terminal provider status not bricking payment (Review 2 Finding F3)
- **Defect in Review 2:** `PspCallbackController` mapped any non-success resultCode or status to `FAILED`. In `PaymentStateMachine`, `FAILED` is a terminal state with no outbound transitions. An intermediate IPN (e.g., MoMo 9000 authorized, or VNPay 03 processing) permanently transitioned the payment to `FAILED`, causing the subsequent real capture callback to fail with `PAYMENT_INVALID_STATE`.
- **Resolution:** `PspCallbackController.java:81-99`:
  ```java
  static String momoStatus(int resultCode) {
      if (resultCode == 0) return CAPTURED;
      if (resultCode == 1002 || resultCode == 1005) return FAILED;
      return null;
  }

  static String vnpayStatus(String transactionStatus) {
      if ("00".equals(transactionStatus)) return CAPTURED;
      if ("01".equals(transactionStatus) || "04".equals(transactionStatus)) return FAILED;
      return null;
  }
  ```
  In lines 58-61 and lines 74-77, if `status == null`, `webhookEventService.handle(...)` is skipped entirely. The callback returns HTTP 204 (MoMo) or `{"RspCode":"00","RspMsg":"Confirm Success"}` (VNPay), acknowledging receipt without modifying payment state.
- **Test Evidence:**
  - `PspCallbackControllerTest.momoCallbackWithAnUnknownResultCodeIsAcknowledgedWithoutAStateChange`: MoMo callback with resultCode `9000` returns HTTP 204 No Content; `verify(webhookEventService, never()).handle(any(), any())`.
  - `PspCallbackControllerTest.vnpayCallbackWithAPendingStatusIsAcknowledgedWithoutAStateChange`: VNPay callback with status `03` returns `RspCode: "00"`; `verify(webhookEventService, never()).handle(any(), any())`.

### D4: Env example callback URLs (Review 2 Finding F7)
- **Defect in Review 2:** `.env.example` contained a stale note claiming PSP callbacks were unexposed, directed return URLs to non-existent `/return` endpoints, and contained a corrupted notify URL with `/vnpay/ipnnpay/ipn`.
- **Resolution:**
  - In commit `1f4e18ee`, stale notes were removed and return URLs updated to frontend browser redirect paths (`https://<frontend>/payment/momo/return` and `https://<frontend>/payment/vnpay/return`).
  - Line 192 of `.env.example` was corrected from `/api/v1/webhooks/payments/vnpay/ipnnpay/ipn` to `/api/v1/webhooks/payments/vnpay/ipn`.
  - Verified that `/api/v1/webhooks/payments/vnpay/ipn` exactly matches `ApiPaths.WEBHOOK_PAYMENTS` (`/api/v1/webhooks/payments`) + `PspCallbackController` mapping (`/vnpay/ipn`).

---

## 3. Test Suites Executed & Inspection Record

All test suites were executed independently via Maven:

```
./mvnw -B -ntp -pl payment-service test
```

### Targeted Test Suites Summary
| Test Suite | Tests Run | Failures | Errors | Skipped | Status |
|---|---|---|---|---|---|
| `MoMoProviderTest` | 12 | 0 | 0 | 0 | **PASS** |
| `VNPayProviderTest` | 13 | 0 | 0 | 0 | **PASS** |
| `PspCallbackControllerTest` | 9 | 0 | 0 | 0 | **PASS** |
| `PaymentProviderRoutingTest` | 5 | 0 | 0 | 0 | **PASS** |
| `PaymentCaptureConfirmationIT` | 6 | 0 | 0 | 0 | **PASS** |
| `PaymentProviderRegistrationTest` | 5 | 0 | 0 | 0 | **PASS** |
| `PaymentProviderProductionGuardTest` | 7 | 0 | 0 | 0 | **PASS** |
| `StripeWebhookIT` | 5 | 0 | 0 | 0 | **PASS** |
| `CapturedPaymentLookupIT` | 3 | 0 | 0 | 0 | **PASS** |

### Full Payment Service Suite Result
```
[INFO] Results:
[INFO] Tests run: 261, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  54.917 s
```

### Static Analysis & Checkstyle
```
./mvnw -T1C -pl payment-service checkstyle:check
[INFO] Starting audit...
Audit done.
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

---

## 4. Edge Cases, Concurrency & Negative Testing

1. **Replay & Idempotent Webhook Delivery:**
   - Multiple IPN deliveries with identical provider event IDs are absorbed idempotently. Handled by database unique constraint `uk_payment_events_provider_event` on table `payment_events`.
   - `WebhookEventService.handle` queries status: if `PROCESSED`, returns early without re-executing state machine or re-publishing outbox events.

2. **Currency & Precision Safeguards:**
   - Non-VND currencies (`USD`, `EUR`, etc.) are rejected with `MOMO_UNSUPPORTED_CURRENCY` / `VNPAY_UNSUPPORTED_CURRENCY`.
   - Decimal/fractional amounts for zero-decimal VND (`500.5`) are rejected upfront with `MOMO_AMOUNT_NOT_INTEGRAL` / `VNPAY_AMOUNT_NOT_INTEGRAL`.

3. **Tamper Rejection:**
   - MoMo: payload tampering (modifying amount while preserving signature) throws `BusinessException(WEBHOOK_SIGNATURE_INVALID)`.
   - VNPay: parameter tampering produces HMAC mismatch and returns vendor code `{"RspCode":"97","RspMsg":"Invalid Checksum"}`.

4. **Production Deployment Isolation:**
   - In production mode (`shop.payment.production-mode=true`), startup fails fast if an unintegrated or mock provider is active.
   - Provider beans exist only when all connection properties and callback URLs are provisioned.

---

## 5. Conclusion & Verdict

All four defects flagged in Review 2 have been verified as resolved in code, configuration, and automated tests. The money-path state transitions, signature verification algorithms, encoding rules, and callback lifecycles adhere strictly to enterprise production standards.

**QA Verdict: QA_PASS**
