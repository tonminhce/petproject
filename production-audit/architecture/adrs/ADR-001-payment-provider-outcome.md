# ADR-001: Payment provider outcomes are explicit, not a boolean

- Status: Accepted
- Context: `ProviderResult(String providerEventId, boolean accepted)` could not express
  the real state of a card payment. `StripeProvider.capture` created a PaymentIntent with
  `confirm=false` and returned `accepted=true`, so `PaymentServiceImpl.capture` wrote
  `CAPTURED` and published `payment.captured.v1` although no money had moved.
- Decision: `ProviderResult` carries a four-value outcome: `CONFIRMED`,
  `REQUIRES_CONFIRMATION`, `REJECTED`, `INDETERMINATE`. `accepted()` is true only for
  `CONFIRMED`. A payment reaches `CAPTURED` only on `CONFIRMED`. A provider timeout is
  `INDETERMINATE`, never `REJECTED`.
- Consequences: the client can complete confirmation because the client secret is
  returned and persisted. The provider intent identifier is stored so a refund addresses
  the original intent instead of searching an eventually consistent index.
- Evidence: `payment-service/.../provider/StripeProvider.java:54-63`,
  `payment-service/.../service/impls/PaymentServiceImpl.java:127-135`.
