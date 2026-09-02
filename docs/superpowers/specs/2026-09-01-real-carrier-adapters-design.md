# Real Carrier Adapters (GHN/GHTK) — Design

- Date: 2026-09-01
- Status: Draft (pending user ratification of 3 scope decisions)
- Scope: turn shipping-service's GHN/GHTK carrier slots into working adapters. Existing `CarrierAdapter` port (shipping D1) defines the contract; ManualCarrierAdapter and NoopCarrierAdapter already implemented. This epic adds GhnAdapter + GhtkAdapter.

## Verified ground truths

- shipping-service D1 defines `CarrierAdapter { Carrier carrier(); ShipmentDraft createShipment(...); void cancelShipment(...); }`.
- shipping-service D4 webhook path is `/api/v1/webhooks/shipping/{carrier}` with HMAC-SHA256 fail-closed verify (existing pattern).
- shipping-service D10 specifies webhook secrets are PER CARRIER (SHOP_SHIPPING_WEBHOOK_SECRET_GHN, SHOP_SHIPPING_WEBHOOK_SECRET_GHTK). Verification looks up secret by `{carrier}` path variable.
- ManualCarrierAdapter and NoopCarrierAdapter exist; this epic does NOT touch them — purely additive.
- Carrier enum in shipping-service has at minimum MANUAL + NOOP; needs +2 values (GHN, GHTK). Verify by reading shipping carrier enum.

## §1 Binding decisions

### D1 — Raw HTTP via RestClient (NOT third-party SDK) (decision Q1)

Both GHN and GHTK have undocumented REST APIs (reverse-engineered; no official Java SDK). The fleet pattern for vendor integrations (search D1: elasticsearch-java; payment D1: stripe-java; storage: aws-sdk) is "use the official client where it exists, otherwise wrap in our own thin RestClient". Since neither carrier has an official SDK, use raw HTTP via the fleet's common RestClient pattern.

API endpoint URLs (V1):
- GHN production: https://online-gateway.ghn.vn/shiip/public-api (legacy) or https://api.ghn.vn (current, OAuth-bearer). Default V1: legacy (no OAuth), documented endpoints in https://docs.ghn.vn.
- GHTK production: https://services.ghtk.vn (token-based auth via x-client-token header).

V1 only supports TEST/SANDBOX endpoints of both carriers. Live keys are ops-only epic (separate).

### D2 — Adapter selection is runtime-env per-carrier (decision Q2)

Each carrier adapter is `@ConditionalOnProperty("shop.shipping.carriers.{name}.enabled")`:

- GhnAdapter activates when `shop.shipping.carriers.ghn.enabled=true` + required token + endpoint.
- GhtkAdapter activates when `shop.shipping.carriers.ghtk.enabled=true` + token + endpoint.
- Both default to disabled in compose; activation via ops env.
- When a carrier is disabled, attempting to ship via that carrier → SHP-10006 CARRIER_NOT_CONFIGURED (existing).

Adapter auto-registration via Spring: a `CarrierAdapterRegistry` (NEW) holds all enabled adapters, exposes `byCarrier(Carrier)` lookup. Used by ShipmentService on CONFIRMED order event to dispatch to the right adapter.

### D3 — Webhook verification per carrier (decision Q3)

Per-carrier webhook secret (existing shipping D10):
- GHN: `SHOP_SHIPPING_WEBHOOK_SECRET_GHN` (HMAC-SHA256, signature header `X-GHN-Signature` — verify GHN's actual scheme at quickstart).
- GHTK: `SHOP_SHIPPING_WEBHOOK_SECRET_GHTK` (HMAC-SHA256, header `X-GHTK-Signature`).

V1 webhook payload mapping (per carrier's actual schema):
- GHN status codes → shipment status (DELIVERED/PICKING/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERY_FAILED per carrier response).
- GHTK status codes → same mapping.

Deduplication: existing `shipment_events` unique index on `(carrier, provider_event_id)` (shipping D8) handles replays.

## §2 Carrier enum + adapter registration

Carrier enum (existing):

```java
public enum Carrier { MANUAL, NOOP, GHN, GHTK }
```

Adapter registry (NEW in shipping-service/.../carrier/CarrierAdapterRegistry.java):

```java
@Component
public class CarrierAdapterRegistry {
    private final Map<Carrier, CarrierAdapter> byCarrier;
    public CarrierAdapterRegistry(List<CarrierAdapter> adapters) {
        this.byCarrier = adapters.stream().collect(toMap(CarrierAdapter::carrier, identity()));
    }
    public Optional<CarrierAdapter> get(Carrier carrier) { return Optional.ofNullable(byCarrier.get(carrier)); }
}
```

## §3 Adapter implementation

### GhnAdapter

- Token: env `SHOP_SHIPPING_CARRIERS_GHN_TOKEN` (issued by GHN).
- Endpoint: env `SHOP_SHIPPING_CARRIERS_GHN_ENDPOINT` (default sandbox https://dev-online-gateway.ghn.vn/shiip/public-api).
- Shop district / province code: from order's shipping address → GHN's required district_id (numeric). V1 limitation: assumes Vietnamese addresses with valid district IDs; foreign addresses → CARRIER_NOT_CONFIGURED for GHN, fallback to GHTK, else MANUAL.
- createShipment: POST /v2/shipping-order/create → returns carrier's tracking code (e.g., `GHN-{tracking}`). Persist on `shipments.tracking_number`.
- cancelShipment: POST /v2/shipping-order/cancel.
- Webhook handling: existing shipping webhook handler dispatches by `{carrier}` path variable; for `carrier=ghn`, payload parsed to `GhnWebhookEvent` → existing `shipment_events` row → state transition via existing handler.

### GhtkAdapter

- Token: env `SHOP_SHIPPING_CARRIERS_GHTK_TOKEN`.
- Endpoint: env `SHOP_SHIPPING_CARRIERS_GHTK_ENDPOINT` (default sandbox https://dev.ghtk.vn).
- createShipment: POST /services/shipment/order → returns tracking code (`GHTK-{tracking}`).
- cancelShipment: POST /services/shipment/cancel.
- Webhook: same pattern as GHN; per-carrier payload schema.

## §4 Configuration

```yaml
shop:
  shipping:
    carriers:
      ghn:
        enabled: ${SHOP_SHIPPING_CARRIERS_GHN_ENABLED:false}
        token: ${SHOP_SHIPPING_CARRIERS_GHN_TOKEN:}
        endpoint: ${SHOP_SHIPPING_CARRIERS_GHN_ENDPOINT:https://dev-online-gateway.ghn.vn/shiip/public-api}
      ghtk:
        enabled: ${SHOP_SHIPPING_CARRIERS_GHTK_ENABLED:false}
        token: ${SHOP_SHIPPING_CARRIERS_GHTK_TOKEN:}
        endpoint: ${SHOP_SHIPPING_CARRIERS_GHTK_ENDPOINT:https://dev.ghtk.vn}
```

Fail-fast: enabled=true + empty token → bean creation fails ("GhnAdapter requires shop.shipping.carriers.ghn.token env").

## §5 API additions

No new endpoints. Carrier selection happens at shipment creation (order CONFIRMED event → ShipmentService.createShipment(order) → look up adapter by configured carrier; default MANUAL if no carrier specified on order).

## §6 Testing strategy

- Unit (adapter): MockRestServiceServer: createShipment happy (GHN returns tracking code, shipment row updated), createShipment 401 from carrier → CARRIER_GATEWAY_ERROR new code, cancelShipment success, fail-fast at startup.
- IT (Testcontainers + WireMock for carrier API): full createShipment → tracking code persisted → carrier webhook with signed payload → state transitions to DELIVERED → shipping.delivered.v1 emitted.
- Manual smoke checklist (ops): sandbox GHN + GHTK accounts, real Vietnamese address → place test order → confirm → carrier shipment created in carrier's dashboard.

## §7 Fleet impact (lane rules)

- shipping-service = W1 lane.
- Carrier enum gains GHN + GHTK constants. Verify pre-existing values before extending (NOOP, MANUAL pre-exist).
- Compose: shipping-service stanza gains optional envs (commented by default — both carriers disabled in compose).
- Outbox/webhook: no new topics; re-use existing shop.shipping.lifecycle.v1.

## §8 Non-goals (binding)

Foreign addresses (non-VN — would need country-specific district code tables; default V1 = VN-only); real/live carrier credentials (V1 sandbox only — ops-only epic for live); address validation per carrier (use whatever address the order has); label PDF generation + storage to common-storage (separate epic); insurance / COD (cash-on-delivery); multi-warehouse / origin-shop selection; rate-shopping (comparing GHN vs GHTK for cheapest); pickup scheduling; bulk-create shipments.

## §9 Open items

- GHN/GHTK API stability: both have changed endpoints without notice in the past; V1 ships with explicit version pin (e.g., GHN API v2). Pin in code; ops can override via env.
- Carrier-side district ID mapping: VN has 700+ districts. V1 ships with hardcoded subset (~50 major districts); full mapping deferred.
- Address validation: V1 passes the address as-is; if carrier rejects, SHP-CARRIER_REJECTED new error → admin notified → admin re-submits via MANUAL.
- Webhook signature schemes: verify with carrier docs at quickstart. GHN historically used MD5, now HMAC-SHA256. Implementation deferred to T2 verification step.
- Sandbox credentials: ops must apply for GHN/GHTK sandbox tokens. Epic does NOT ship with sandbox creds — they're ops-set envs.

## §10 Changelog

- 2026-09-01 (rev 0): Initial draft pending user ratification of D1 (raw HTTP via RestClient), D2 (per-carrier env activation), D3 (webhook via existing shipping D10 mechanism).