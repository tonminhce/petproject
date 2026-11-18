# ADR-005: Authorisation guards fail closed

- Status: Accepted
- Context: `ServiceCallerGuard.requireCaller` returned without error when the security
  context was empty. A guard that allows when it cannot prove the caller is an
  allow-by-default primitive. The same audit found a cross-service endpoint that accepts
  an arbitrary user identifier with no check against the authenticated principal.
- Decision: An authorisation guard raises when the security context is empty or the
  principal is missing. A caller that acts on behalf of another user must present an
  authorised-party identity bound to that user.
- Consequences: an unauthenticated request to a guarded endpoint is rejected rather
  than allowed. The verified-purchase endpoint binds the requested user to the
  authenticated principal.
- Evidence: `utils/common-security/.../service/ServiceCallerGuard.java:27-37`,
  `order-service/.../controller/OrderController.java:81-92`.
