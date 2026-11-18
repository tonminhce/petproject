# ADR-002: An unknown payment provider is rejected, never substituted

- Status: Accepted
- Context: `PaymentProviderFactory.getProvider` returned the default provider for an
  unknown name. `MockProvider.capture` and `MockProvider.refund` always return
  `accepted=true`. A refund routed to the wrong provider could therefore be marked
  `REFUNDED` with no money returned.
- Decision: `getProvider` raises `ErrorCode.PAYMENT_PROVIDER_REJECTED` for an unknown
  provider. The mock provider is selectable only when it is the configured provider. The
  primary provider bean fails fast when the configured provider is not registered
  instead of falling back to an arbitrary provider.
- Consequences: a misconfigured deployment fails at the point of use with a domain error
  rather than silently moving money through the wrong provider.
- Evidence: `payment-service/.../provider/PaymentProviderFactory.java:34-43`,
  `payment-service/.../provider/MockProvider.java:19-26`.
