# Concurrency and Multi-Instance Findings

Each finding follows the required chain: concurrent actors, shared resource, dangerous
interleaving, violated invariant, root cause, proposed fix, reproduction test,
verification.

## 1. Inventory reservation hold is not idempotent

- Actors: two concurrent checkout requests for the same cart.
- Shared resource: the stock row and the reservation table.
- Interleaving: both requests read the available quantity, both pass the availability
  check, and both insert a hold.
- Violated invariant: one logical checkout must produce at most one hold.
- Root cause: the reserve call has no idempotency key and no unique constraint that
  binds a hold to its request.
- Fix: bind the hold to a caller-supplied idempotency key under a unique constraint, so
  a duplicate request returns the existing hold instead of creating a second one.
- Reproduction: two concurrent reserve calls with the same key; exactly one hold row
  and one stock decrement.
- Verification: an integration test against real PostgreSQL 16 with two threads.
- Task: `OI-01`.

## 2. Outbox claim serialises on the second replica

- Actors: two outbox relay replicas of the same service.
- Shared resource: the pending outbox rows.
- Interleaving: both replicas evaluate a claim query that selects the minimum pending
  identifier. The first replica locks that row; the second replica applies a skip-locked
  read, which yields no candidate at all, so the second replica publishes nothing.
- Violated invariant: every pending event must eventually be published by some replica.
- Root cause: the claim query restricts the candidate set to one row before the lock is
  taken, so a locked row removes the only candidate.
- Fix: claim over the whole claimable set and order inside the transaction, so a
  replica that finds the first row locked moves on to the next candidate.
- Reproduction: two concurrent relays over the same pending rows; the union of
  published events covers every pending row.
- Verification: a concurrency integration test that runs two relays against one
  PostgreSQL instance.
- Task: `KAF-01`.

## 3. Payment capture reported success with no money moved

- Actors: a client capture request and the payment provider.
- Shared resource: the payment row and the provider intent.
- Interleaving: the adapter created a payment intent with confirmation disabled and
  returned an accepted result, so the service wrote the captured status and published
  the capture event while the intent was still awaiting confirmation.
- Violated invariant: a captured payment means the provider verified that the money
  moved for exactly the stored amount and currency.
- Root cause: a two-field result could not express "awaiting client confirmation".
- Fix: an explicit outcome vocabulary, and a captured status only on a confirmed
  outcome.
- Reproduction: a provider stub that returns an unconfirmed intent; the payment stays
  pending and no capture event is published.
- Verification: `PaymentCaptureConfirmationIT` asserts the persisted payment row and
  the outbox rows. Executed, passed.
- Task: `PAY-01`, state `VERIFIED`.

## 4. A refund could execute against the wrong provider

- Actors: a refund request and the provider registry.
- Shared resource: the provider registry and the payment status column.
- Interleaving: an unknown provider name resolved to the default provider, and the
  default mock provider accepts every call, so the payment was marked refunded while
  no money moved.
- Violated invariant: only the provider that owns the original intent may move money
  for that payment.
- Root cause: a silent fallback instead of an explicit rejection.
- Fix: reject an unknown provider with the domain error, and make the mock provider
  exist only when it is the configured provider.
- Reproduction: a payment whose provider is not registered; the refund is rejected and
  the status is unchanged.
- Verification: `PaymentProviderRoutingTest` and `PaymentCaptureConfirmationIT`.
  Executed, passed.
- Task: `PAY-02`, state `VERIFIED`.

## 5. The gateway rate-limit identity was attacker controlled

- Actors: any client, the gateway replica, and Redis.
- Shared resource: the rate-limit bucket key.
- Interleaving: the client address was taken from an indexed entry of the forwarding
  header without checking the transport peer, so the client chose its own budget
  bucket and its own allowlist verdict.
- Violated invariant: the rate-limit identity must be the address the platform actually
  observed.
- Root cause: a hop count used as an index into an attacker-controlled header.
- Fix: resolve the address from the peer, consult the forwarding header only through a
  configured trusted proxy list, and validate the result as a literal address before it
  becomes a bucket key.
- Reproduction: a request with a forged forwarding header must land in the same bucket
  as the peer address.
- Verification: a gateway filter unit test plus a multi-replica test against one Redis.
- Task: `GW-01`, `GW-02`.

## 6. A crafted forwarding header caused name resolution on the event loop

- Actors: any client, the gateway event loop, and the resolver.
- Shared resource: the event loop thread and the Redis key space.
- Interleaving: a non-literal forwarding entry was resolved by name, which blocks the
  event loop, and the resolved hostname became the bucket key, so every request
  received a fresh full budget and created a new Redis key.
- Violated invariant: the event loop never blocks on name resolution, and the key space
  is bounded.
- Root cause: an address that was never validated as an address.
- Fix: the same validation as finding 5, applied before the value is used.
- Reproduction: a forwarding header with a hostname must not create a new bucket and
  must not resolve a name.
- Verification: a gateway filter unit test that asserts no resolution is attempted.
- Task: `GW-02`.

## 7. The authorization guard failed open

- Actors: an unauthenticated request and the shared authorization guard.
- Shared resource: the security context.
- Interleaving: the guard read the security context, found no authentication, and
  returned without error, so the request was allowed.
- Violated invariant: an authorization guard that cannot prove the caller must deny.
- Root cause: an empty context was treated as "nothing to check".
- Fix: raise when the context is empty or the principal is missing.
- Reproduction: a call with an empty security context must raise.
- Verification: a shared-library unit test.
- Task: `SL-24`.

## 8. Cross-service read of another user's order items

- Actors: any service-role client and the order service.
- Shared resource: the delivered order items of an arbitrary user.
- Interleaving: the endpoint accepted the requested user identifier as a parameter and
  filtered only on it, so a service-role caller could read any user's delivered items.
- Violated invariant: a caller may only read the order data of the user it acts for.
- Root cause: no binding between the authenticated principal and the requested
  identifier.
- Fix: bind the requested identifier to the authenticated principal, and require an
  authorised-party identity that is bound to that user for a delegated read.
- Reproduction: a service-role token requesting another user's identifier must be
  rejected.
- Verification: a controller authorization test.
- Task: `SEC-02`.

## 9. The platform user was resolved in the wrong key space

- Actors: an authenticated user and the user service.
- Shared resource: the user table.
- Interleaving: the controller passed the token subject into a lookup by the generated
  primary key, so the row was never found and the self-service surfaces failed.
- Violated invariant: the identity provider identifier is the only key that resolves a
  platform user.
- Root cause: two identifier spaces used interchangeably.
- Fix: resolve by the identity provider identifier column.
- Reproduction: a token subject resolves to the user row.
- Verification: an auth-service controller test.
- Task: `SEC-01`.

## 10. The consumer mapper rejected additive event changes

- Actors: a producer that adds a field, and every consumer of that event.
- Shared resource: the consumer deserializer.
- Interleaving: the consumer mapper was configured to reject unknown properties while
  the application mapper accepted them, so one additive field turned every record into
  a poison record and every consumer dead-lettered its entire backlog.
- Violated invariant: an event contract can evolve additively.
- Root cause: a bare object mapper that did not inherit the application configuration.
- Fix: configure the wire mapper like the application mapper.
- Reproduction: a record with an extra field deserialises successfully.
- Verification: a shared-library unit test.
- Task: `SL-08`.

## 11. The dead-letter replay republished the whole topic

- Actors: an operator and the replay service.
- Shared resource: the dead-letter topic and its consumer offsets.
- Interleaving: each replay run created a consumer group that started from the
  earliest offset, so every run republished the entire dead-letter topic, duplicating
  business events that had already been replayed once.
- Violated invariant: a replay publishes the events that have not been replayed.
- Root cause: the committed offset of a record was discarded.
- Fix: keep the offsets across runs.
- Reproduction: two consecutive replay passes publish each record once in total.
- Verification: a shared-library test with two passes.
- Task: `SL-05`.

## 12. Verified safe, do not change

- The eight outbox implementations commit the business write and the outbox insert in
  one transaction.
- The eight claim queries use a pessimistic write lock with a skip-locked timeout.
- The mark-as-sent step is a compare-and-set update.
- The publish step never holds a database lock.
- The gateway buckets mutate state with a single Lua script, so they are atomic across
  replicas. The audit initially suspected a per-instance bucket; the source proves
  otherwise.
- The payment webhook store keeps the provider event identifier under a unique
  constraint, so a redelivered event is a no-op at the store.
- The notification, shipment and order-confirm consumers keep a durable unique record.
