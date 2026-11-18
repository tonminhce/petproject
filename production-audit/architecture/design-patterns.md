# Design Pattern Adoption

Every pattern below was evaluated against the actual source. "Present" means the
pattern is genuinely implemented and the evidence is a real code path, not a class name.
"Proposed" means the pattern is justified by a proven defect and is either applied in
this run or scheduled with an acceptance criterion.

## Matrix

| Pattern | common-core | common-kafka | common-security | common-spring | common-keycloak | common-storage | auth | product | order | payment | inventory | shipping | search | promotion | tax | rating | media | favourite | notification | gateway |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Singleton | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y |
| Factory Method | - | Y | - | Y | - | Y | - | - | - | Y | - | Y | - | - | - | - | - | - | - | - |
| Abstract Factory | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Builder | - | - | - | - | - | - | - | Y | Y | - | - | - | - | - | - | - | Y | - | - | - |
| Adapter | - | Y | - | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | - | - | Y | Y | - | Y | - |
| Decorator | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Facade | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Proxy | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Composite | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Strategy | - | - | - | Y | - | - | - | - | - | Y | - | - | - | - | - | - | Y | - | - | - |
| State | - | - | - | - | - | - | - | - | Y | Y | Y | Y | - | - | - | - | - | - | - | - |
| Observer | - | Y | - | - | - | - | - | Y | Y | Y | Y | Y | Y | Y | - | Y | Y | - | Y | - |
| Chain of Responsibility | - | - | - | Y | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | Y |
| Template Method | - | Y | - | Y | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Command | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Saga | - | - | - | - | - | - | - | - | Y | - | Y | - | - | - | - | - | - | - | - | - |
| Transactional Outbox | - | Y | - | - | - | - | - | Y | Y | Y | Y | Y | - | Y | - | Y | Y | - | Y | - |
| Idempotent Consumer | - | Y | - | - | - | - | - | Y | Y | Y | Y | Y | Y | - | - | Y | - | - | Y | - |
| Domain Events | - | Y | - | - | - | - | - | Y | Y | Y | Y | Y | Y | Y | - | Y | Y | - | Y | - |
| Repository | - | - | - | - | - | - | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | - |
| Specification | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Cache-Aside | - | - | - | - | Y | - | - | Y | Y | - | Y | - | Y | - | - | - | - | - | - | - |
| Circuit Breaker | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Bulkhead | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Retry | - | Y | - | - | - | - | - | Y | Y | Y | - | Y | - | - | - | - | - | - | - | - |
| Reservation | - | - | - | - | - | - | - | - | Y | - | Y | - | - | Y | - | - | - | - | - |
| Optimistic Locking | - | - | - | - | - | - | - | Y | Y | Y | Y | Y | - | Y | - | Y | - | - | Y | - |
| Pessimistic Locking | - | - | - | - | - | - | - | Y | Y | Y | Y | Y | - | Y | - | Y | - | - | - | - |
| Reconciliation Sweeper | - | - | - | - | - | - | - | Y | Y | Y | Y | Y | - | - | - | Y | - | - | Y | - |

`Y` means the pattern is present with evidence in the source.

## Patterns genuinely present, with evidence

**Singleton.** Spring manages one instance per application context. This is a lifecycle
decision, not a concurrency guarantee. Every audited singleton was inspected for shared
mutable state; 32 unsafe constructs were recorded. The confirmed safe ones include
`KafkaMessagePublisher.publishBatch`, which writes the outcome before the countdown and
therefore establishes the correct happens-before edge, and `BoundedAsyncAuditEventWriter`,
which uses atomic counters and a synchronized sink.

**Factory Method.** `PaymentProviderFactory` resolves a provider from a registry.
`S3ClientFactory` creates the object-storage client. `CarrierFactory` selects the carrier
adapter. `PaymentProviderConfig.primary` selects the wired provider.

**Strategy.** `PaymentProvider` implementations are interchangeable strategies behind
one port. `AuditEventWriter` and `ObjectStorageService` are strategies selected with a
`@ConditionalOnMissingBean` fallback.

**Adapter.** `S3ObjectStorageService` adapts the AWS SDK to the storage port. The Keycloak
clients adapt the REST client to the identity port. `JsonKafkaSerializer` and
`JsonKafkaDeserializer` adapt the JSON contract to the Kafka contract.
`JwtRolesConverter` adapts the token claims to the role model.

**State.** `PaymentStateMachine` and `ShipmentStateMachine` guard status transitions.

**Template Method.** `BaseKafkaConsumer`, `BaseKafkaListenerConfig`, `ApiExceptionHandler`,
`AuditAspect` and `LoggerAspect` all fix a skeleton and leave the varying steps to
subclasses.

**Observer and Domain Events.** The transactional outbox is the platform-wide
implementation: a business write publishes a domain event, and the outbox relays it.

**Transactional Outbox.** Eight services. The atomicity guarantee holds in all eight:
the business write and the outbox insert commit in one transaction.

**Idempotent Consumer.** Durable deduplication exists for the three consumers that
perform a side effect. The remaining consumers are idempotent by snapshot assignment,
which is correct but unenforced.

**Pessimistic Locking and Reservation.** The inventory reservation holds a pessimistic
row lock, and the eight outbox relays claim pending rows with a skip-locked pessimistic
write lock.

**Optimistic Locking.** The platform entities carry a version column, and the outbox
mark-as-sent step is a compare-and-set update.

**Reconciliation Sweeper.** Eight retention and reconciliation schedulers exist.

**Chain of Responsibility.** The gateway filter chain applies authentication, the admin
allowlist, the rate limiters and the routing filters in order.

## Patterns proposed and the justification

| Pattern | Where | Problem it solves | Decision |
| --- | --- | --- | --- |
| Strategy with an explicit outcome vocabulary | payment provider port | A boolean could not express "the intent exists but the client must confirm", so a capture reported success while no money moved | Applied |
| Specification and Policy | payment provider selection | An unknown provider silently fell back to the default provider, so a refund could succeed against the wrong provider | Applied |
| Specification and Policy | Kafka consumer strictness | The consumer mapper rejected unknown properties, so one additive field broke every consumer of that event | Applied |
| Chain of Responsibility with a fail-closed guard | shared authorization guard | The guard returned without error when the security context was empty, so it allowed unauthenticated callers | Applied |
| Proxy and trusted-peer resolution | gateway client address | The forwarding header was indexed by a hop count without checking the peer, so a crafted header bypassed the allowlist and the budget | Applied |
| Repository with a scoped unique constraint | payment idempotency | The unique index was global while the lookup was scoped per user | Applied |
| Idempotent Consumer with an inbox | outbox relay claim | Six of eight relays used a minimum-identifier subquery, so a second replica selected nothing and published nothing | Applied |
| Reservation with an idempotency key | inventory reserve | The reserve call was not idempotent, so a duplicate request created a second hold | Applied |
| Saga with a durable compensation ledger | order confirm compensation | A failed release was dropped with no ledger and no retry | Applied |
| Circuit Breaker and Bulkhead | gateway route filters | The gateway has exactly one filter per route and no isolation between downstream failures | Proposed |
| Specification for currency | money value objects | No money field in the fleet carries a currency | Proposed |
| Versioned external version in the search index | search projection | An out-of-order event overwrote newer state | Proposed |

## Patterns deliberately not introduced

- **Abstract Factory.** There is one object-storage port and one payment provider
  family. A second level of indirection would add no behaviour.
- **Decorator.** The functional equivalents exist as servlet filters, aspects and
  Spring-managed strategy beans. Wrapping them in decorators would duplicate the
  framework.
- **Composite.** No tree-shaped aggregate with uniform child handling was found.
- **Command.** No use case needs to be captured, queued and executed with a different
  executor.
- **Repository abstraction over Spring Data.** The services already use Spring Data
  repositories directly. Adding a hand-written repository interface would duplicate the
  framework without changing a boundary.
