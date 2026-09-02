# Real Carrier Adapters (GHN/GHTK) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** shipping-service gains two new CarrierAdapter implementations (GHN, GHTK). Both sandbox-only by default; ops-set envs activate. ManualCarrier + NoopCarrier unchanged.

**Architecture:** Two new adapter classes implement existing CarrierAdapter port. CarrierAdapterRegistry auto-collects enabled adapters. Webhook handling re-uses existing shipping D10 per-carrier secret pattern. Spec is binding authority: docs/superpowers/specs/2026-09-01-real-carrier-adapters-design.md.

**Tech Stack:** Spring Boot 4 (fleet BOM), common-core/common-spring, spring RestClient (fleet pattern). No new deps.

**Spec:** docs/superpowers/specs/2026-09-01-real-carrier-adapters-design.md

## Global Constraints

- Both carriers default DISABLED in compose (env opt-in).
- Per-carrier webhook secret pattern: SHOP_SHIPPING_WEBHOOK_SECRET_GHN, SHOP_SHIPPING_WEBHOOK_SECRET_GHTK.
- V1 sandbox endpoints only (live keys are ops-only epic).
- VN addresses only (district ID mapping subset).
- Fail-fast: enabled=true + empty token → bean creation fails.
- i18n: no new keys (SHP-10xxx block covers errors).

---

### Task 1: Carrier enum + adapter registry + properties

**Files:**
- Modify: shipping-service/.../carrier/Carrier.java (existing enum) — add GHN, GHTK constants after MANUAL, NOOP.
- Create: shipping-service/.../config/ShippingCarriersProperties.java (@ConfigurationProperties shop.shipping.carriers: Map<String, CarrierConfig> where CarrierConfig = (enabled, token, endpoint)).
- Create: shipping-service/.../carrier/CarrierAdapterRegistry.java (auto-injects List<CarrierAdapter>, builds Map<Carrier, CarrierAdapter> by carrier()).
- Modify: shipping-service/.../service/ShipmentService.java (or wherever carrier dispatch happens) — change from `if/else` on carrier to `registry.get(carrier).orElseThrow(() -> new BusinessException(SHP-10006))`.
- Test: CarrierAdapterRegistryTest (Mockito): two adapters registered, get(GHN) returns GhnAdapter, get(UNKNOWN) returns empty.

- [ ] **Step 1: failing test** for registry.
- [ ] **Step 2: implement.** Use Spring's List<CarrierAdapter> auto-injection; manual lookup is replaced.
- [ ] **Step 3: run** ./mvnw -pl shipping-service test → GREEN.
- [ ] **Step 4: commit** feat(shipping): Carrier enum +2 + adapter registry

### Task 2: GhnAdapter implementation + RestClient

**Files:**
- Create: shipping-service/.../carrier/ghn/GhnAdapter.java (@Component @ConditionalOnProperty("shop.shipping.carriers.ghn.enabled=true") implements CarrierAdapter):
  - carrier() returns Carrier.GHN.
  - createShipment(): POST {endpoint}/v2/shipping-order/create with body {order details, district_id} + header Token={token}; parse response for tracking_code; if carrier returns error code, throw BusinessException SHP-CARRIER_REJECTED (new error code).
  - cancelShipment(): POST {endpoint}/v2/shipping-order/cancel.
- Create: shipping-service/.../carrier/ghn/GhnClient.java (RestClient wrapper, timeouts 5000ms; bearer auth via Token header).
- Modify: shipping-service/.../config/RestClientConfig.java — add ghnRestClient bean conditional on GhnAdapter enabled.
- Test: GhnAdapterTest (MockRestServiceServer): createShipment 200 → returns ShipmentDraft with tracking GHN-XXX; createShipment 401 → SHP-CARRIER_REJECTED; cancelShipment happy; constructor fail-fast on empty token.

- [ ] **Step 1: failing tests** (4+ cases).
- [ ] **Step 2: implement.** Verify GHN's actual response shape at quickstart via curl against sandbox (manual smoke checklist step).
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(shipping): GhnAdapter + RestClient (sandbox)

### Task 3: GhtkAdapter implementation + RestClient

**Files:**
- Create: shipping-service/.../carrier/ghtk/GhtkAdapter.java (@Component @ConditionalOnProperty("shop.shipping.carriers.ghtk.enabled=true") implements CarrierAdapter):
  - carrier() returns Carrier.GHTK.
  - createShipment(): POST {endpoint}/services/shipment/order with header x-client-token={token}; parse response for tracking_code.
  - cancelShipment(): POST {endpoint}/services/shipment/cancel.
- Create: shipping-service/.../carrier/ghtk/GhtkClient.java.
- Test: GhtkAdapterTest (similar to Ghn).

- [ ] **Step 1: failing tests** (4+ cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(shipping): GhtkAdapter + RestClient (sandbox)

### Task 4: webhook handlers for GHN + GHTK

**Files:**
- Modify: shipping-service/.../controller/WebhookController.java (or existing webhook handler) — branch on carrier=ghn vs ghtk to parse per-carrier payload (GhnWebhookEvent, GhtkWebhookEvent). Existing webhook handler logic (HMAC verify, dedupe, state transition) re-uses per-carrier secret lookup.
- Create: shipping-service/.../dto/ghn/GhnWebhookEvent.java (record: carrier, code, status, order_code, etc.).
- Create: shipping-service/.../dto/ghtk/GhtkWebhookEvent.java (record: similar fields).
- Modify: shipping-service/.../service/ShipmentWebhookService.java — add carrier-specific payload parsers.
- Test: WebhookHandlerTest: GHN payload with status=DELIVERED → shipment DELIVERED + outbox shipping.delivered.v1; GHTK similar; HMAC signature failure → 401 SHP-10004.

- [ ] **Step 1: failing tests** (3 cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(shipping): GHN/GHTK webhook handlers + payload mappers

### Task 5: error code for carrier rejection

**Files:**
- Modify: utils/common-core/.../exception/ErrorCode.java — flip SRC-13004 ; → , append SHP-10007 CARRIER_REJECTED (502, carrier API rejected the request) with ; terminator. Verify by grep first.
- Modify: utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties — add shipping.carrier_rejected.

- [ ] **Step 1: implement** code + i18n key.
- [ ] **Step 2: run** compile PASS.
- [ ] **Step 3: commit** feat(shipping): SHP-10007 CARRIER_REJECTED error code

### Task 6: IT — full GHN sandbox flow

**Files:**
- Create: shipping-service/.../GhnAdapterIT.java (Testcontainers + WireMock): stub GHN API at WireMock; full createShipment round-trip → tracking persisted → webhook with signed payload → shipment DELIVERED → outbox event asserted.

- [ ] **Step 1: failing test** → implement → GREEN.
- [ ] **Step 2: commit** test(shipping): GhnAdapter IT (sandbox via WireMock)

### Task 7: compose + manual smoke checklist

**Files:**
- Modify: docker-compose.yml — shipping-service stanza: ADD commented env block for SHOP_SHIPPING_CARRIERS_GHN_* and SHOP_SHIPPING_CARRIERS_GHTK_*:

```yaml
  # To activate GHN (sandbox):
  # - SHOP_SHIPPING_CARRIERS_GHN_ENABLED=true
  # - SHOP_SHIPPING_CARRIERS_GHN_TOKEN=your-ghn-sandbox-token
  # - SHOP_SHIPPING_WEBHOOK_SECRET_GHN=shared-secret-with-ghn
  # To activate GHTK (sandbox):
  # - SHOP_SHIPPING_CARRIERS_GHTK_ENABLED=true
  # - SHOP_SHIPPING_CARRIERS_GHTK_TOKEN=your-ghtk-sandbox-token
  # - SHOP_SHIPPING_WEBHOOK_SECRET_GHTK=shared-secret-with-ghtk
```

- [ ] **Step 1:** docker compose config -q → exit 0.
- [ ] **Step 2:** ./mvnw -T1C install -DskipTests -q → exit 0.
- [ ] **Step 3:** Manual smoke checklist (NOT automated):
  1. Apply for GHN/GHTK sandbox accounts.
  2. Set env vars above; docker compose up -d shipping-service.
  3. POST /api/v1/webhooks/shipping/ghn with signed test payload → shipment state updates.
  4. Verify in carrier dashboard that test shipment created.
- [ ] **Step 4: commit** chore(shipping): compose carrier env templates + manual smoke checklist

### Task 8: final whole-branch review

- [ ] Dispatch reviewer subagent: whole-branch diff vs main; spec D1-D3 + §7 audit; GhnAdapter + GhtkAdapter wired into CarrierAdapterRegistry; per-carrier activation env-gated; webhook signature per-carrier; fail-fast at startup; zero-regression on ManualCarrier/NoopCarrier; tracking code format consistent (carrier-prefixed); i18n keys for CARRIER_REJECTED. Fix rounds until APPROVED.