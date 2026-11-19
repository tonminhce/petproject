# Current Architecture

Reconstructed from source at baseline revision `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`.
Every statement below is anchored to a file and line. Claims carried over from prior
documentation that source contradicts are marked as corrections.

## 1. Runtime topology

`docker-compose.yml` defines 21 services: 7 infrastructure components and 14 backend
processes.

| Layer | Services |
| --- | --- |
| Infrastructure | `postgres`, `redis`, `kafka`, `elasticsearch`, `rustfs`, `keycloak` |
| Edge | `gateway-service` |
| Identity | `auth-service` |
| Catalog | `product-service`, `search-service`, `media-service` |
| Transaction | `order-service`, `payment-service`, `inventory-service`, `shipping-service`, `promotion-service`, `tax-service` |
| Engagement | `notification-service`, `rating-service`, `favourite-service` |
| Test double | `mock-payment-provider` |

Production adds an nginx ingress in front of the gateway (`k8s/ingress.yaml:6,23-29`),
with the gateway running 3 replicas behind an HPA of 2 to 10
(`k8s/gateway-service.yaml:10,74-92`).

## 2. Correction: the edge is Spring Cloud Gateway, not Apache APISIX

`pom.xml:49` carries the comment `gateway migrated to Apache APISIX`. That comment is
wrong. A repository-wide search for `apisix` matches only prose in `pom.xml`,
`docs/` and `plan.md`. There is no APISIX, nginx, Envoy or Traefik configuration in the
repository, and `gateway-service/pom.xml:37-40` depends on
`spring-cloud-gateway-server-webflux:5.0.0`.

The real request path is:

```
client -> nginx ingress (k8s) -> gateway-service:8080
       -> Security WebFilter chain (SecurityConfig.java:37-49)
       -> GlobalFilters (FilterOrder.java:15-19)
       -> per-route filters (RoutesConfig.java:31-48)
       -> http://<service>:<port> (ServiceRoute.java:10-39)
```

In compose the gateway container is published on the host and reaches the other services
by compose DNS. No reverse proxy sits inside the compose network.

## 3. Correction: the shared library platform has 7 modules, not 8

`utils/pom.xml` declares seven modules: `common-core`, `common-security`,
`common-logging`, `common-keycloak`, `common-kafka`, `common-spring`, `common-storage`.
`utils/common-patterns` is not a Maven module. It has no `pom.xml`, it is absent from the
module list, and it contains no git-tracked file. Only an untracked `.flattened-pom.xml`
and a stale `target/` directory remain. The library was replaced by the Checkstyle and
SonarQube quality gate in commit `5d6ba25`.

## 4. Module dependency graph

Derived from the Maven POMs, not from package names.

- `common-core` is the only leaf that the other libraries depend on.
- `common-kafka` and `common-storage` depend on nothing internal.
- `common-spring` aggregates the others and pulls Kafka and storage optionally.
- Every service depends on one or more shared libraries; no shared library depends on a
  service.
- 78 internal edges, zero cycles, zero layering violations.

## 5. Identity

Keycloak 26 issues the tokens. `auth-service` does not sign anything: it forwards the
resource-owner password credentials grant to
`utils/common-keycloak/.../client/KeycloakTokenClient.java:53-64`. The issuer is
`http://keycloak:8080/realms/ecommerce` and the algorithm is RS256.

Validation is centralized in `utils/common-security/.../config/BaseSecurityConfig.java:122-145`:
issuer and timestamp are always validated; audience and authorised-party checks are
conditional on configuration.

Authorization is default-deny: `BaseSecurityConfig.java:83` requires authentication for
any request, and the gateway requires an authenticated exchange
(`gateway-service/.../config/SecurityConfig.java:46`).

## 6. Synchronous communication

Service-to-service calls use HTTP clients declared per service, for example
`product-service/.../client/InventoryReservationClient.java` and
`product-service/.../client/MediaHeadClient.java`. Every outbound URL is derived from
configuration, and no service concatenates user input into an outbound request.

## 7. Asynchronous communication

The topic contract is centralised in
`utils/common-kafka/.../contract/KafkaTopicContract.java:70-82` and enforced by
`KafkaTopicContractTest.java:123-149`, which fails the build when a topic literal
appears outside the contract.

| Topic | Producer | Consumers | Partition key |
| --- | --- | --- | --- |
| `shop.order.lifecycle.v1` | order-service | notification-service, shipping-service | orderId |
| `shop.product.lifecycle.v1` | product-service | search-service | productId |
| `shop.category.lifecycle.v1` | product-service | none | categoryId |
| `shop.brand.lifecycle.v1` | product-service | none | brandId |
| `shop.inventory.events.v1` | inventory-service | none | productId |
| `shop.payment.lifecycle.v1` | payment-service | order-service | paymentId |
| `shop.shipping.lifecycle.v1` | shipping-service | order-service | shipmentId |
| `shop.rating.lifecycle.v1` | rating-service | product-service | productId |
| `shop.promotion.lifecycle.v1` | promotion-service | none | campaignId |
| `media.lifecycle.v1` | media-service | product-service | mediaId |

The Kafka producer is configured for `acks=all`, `enable.idempotence=true` and
`max.in.flight.requests.per.connection=1`
(`utils/common-kafka/.../config/KafkaProperties.java:83-91`). No producer transaction
identifier is configured anywhere in the repository, so the platform provides at-least-once
publication and relies on consumer-side idempotency.

## 8. Transactional outbox

Eight services implement an outbox: order, product, media, rating, shipping, payment,
promotion and inventory. In all eight the business write and the outbox insert are
committed in one database transaction, the pending rows are claimed with a pessimistic
write lock using a skip-locked lock timeout, the mark-as-sent step is a compare-and-set
update, and the publish step never holds a database lock.

Consumer idempotency is durable for the three consumers that perform a side effect:
notification uses a unique event identifier, shipment uses a unique live-order
constraint, and order confirmation uses the `idempotency_keys` primary key on
`(actor, key)`. The remaining consumers are idempotent by snapshot assignment.

## 9. Payment

`payment-service` implements the provider abstraction as a registry and factory
(`payment-service/.../provider/PaymentProviderFactory.java`) over a `PaymentProvider`
port. Registered providers are Stripe, MoMo, VNPay, cash on delivery and a mock provider.
The provider is chosen per payment row.

The payment state machine is `payment-service/.../service/PaymentStateMachine.java`.
Webhook verification uses the official Stripe SDK for Stripe and an HMAC comparison for
the other providers. The webhook body is received as raw bytes and verified before any
parsing (`payment-service/.../webhook/PaymentWebhookController.java:33`).

## 10. Gateway rate limiting

The platform reports two token-bucket layers. Source shows three, all Redis-backed and
all mutated with a single Lua script so the bucket is atomic across replicas.

| Layer | Scope | Key | Limit |
| --- | --- | --- | --- |
| `RoutesConfig.java:37-43` | per route | `request_rate_limiter.{routeId}.{user or ip}` | 100/s, burst 200 |
| `GlobalRateLimitFilter.java:20-21` | fleet-wide | one key `gateway-system` | 2000/s, burst 4000 |
| `RedisEdgeRateLimiter.java:45-73` | backoffice and search | `gateway:edge-rate-limit:{scope}:{ip}` | 10/min and 60/min |

There is no Bucket4j dependency anywhere in the repository.

## 11. Storage

Each service owns its own PostgreSQL 16 database and its own Liquibase changelog set.
Redis backs the gateway rate limiting and the service caches. Elasticsearch backs
`search-service`. RustFS provides S3-compatible object storage for `media-service`.

The Elasticsearch server is 8.15.0 (`docker-compose.yml:98`). The root POM declares the
client at 9.4.5 (`pom.xml:54`), but `search-service/pom.xml:35-37` overrides it, so the
effective client is 8.15.5 and matches the server. The declared property is misleading
and is recorded as finding RES-23.

The Kafka broker is 3.9.0 (`docker-compose.yml:69`) while the managed Kafka client is
4.2.1. The broker runs with a replication factor of 1 and no producer transaction
identifier is configured anywhere, so the platform provides at-least-once publication.
The integration tests run against `confluentinc/cp-kafka:7.6.0`, which is a different
broker distribution from the deployed `apache/kafka:3.9.0`; that mismatch is recorded as
finding RES-22.

## 12. Test strategy

Unit tests are Surefire `*Test` classes. Integration tests are Failsafe `*IT` classes
that run against real PostgreSQL 16 and Kafka containers through Testcontainers 2.0.5.
The baseline reactor build ran 1913 tests with zero failures, zero errors and zero
skips.
