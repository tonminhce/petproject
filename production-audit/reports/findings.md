# Findings Register

Generated: 2026-11-10T10:05:42

Total findings: 68

| ID | Priority | Category | Title | Status |
|---|---|---|---|---|
| F-001 | P1 | distributed-systems | Kafka decode failures are silently dropped: no DLT, no retry, no opera | OPEN |
| F-002 | P2 | distributed-systems | Four published lifecycle topics have no consumer: inventory, promotion | OPEN |
| F-003 | P3 | configuration | Dead legacy topic-name configuration in KafkaProperties.Topics | OPEN |
| F-004 | P1 | security | OAuth2 ROPC grant used for end-user login and for the Keycloak master- | OPEN |
| F-005 | P1 | security | JWT audience validation is implemented but never configured - a token  | VERIFIED |
| F-006 | P2 | security | Shared default config ships default Keycloak master-realm admin creden | OPEN |
| F-007 | P3 | security | Contradictory bean-validation constraints on SecurityProperties.Cors.m | OPEN |
| F-008 | P3 | security | Object-storage access/secret key default to rustfsadmin in the shared  | OPEN |
| F-009 | P2 | security | server.error.include-message=always exposes internal exception text to | OPEN |
| F-010 | P1 | correctness | Stock columns have no non-negative invariant: reserved_quantity/availa | VERIFIED |
| F-011 | P1 | correctness | Reservation row has no version column: the expiry sweep can overwrite  | VERIFIED |
| F-012 | P2 | distributed-systems | Expiry sweep releases stock without emitting the inventory.released.v1 | VERIFIED |
| F-013 | P2 | performance | Outbox relay holds a PESSIMISTIC_WRITE row lock and an open DB transac | OPEN |
| F-014 | P2 | correctness | Reservation retry loop swallows the interrupt flag and keeps retrying  | VERIFIED |
| F-015 | P3 | database | reservations table has no index on order_id and no foreign key to inve | OPEN |
| F-016 | P1 | correctness | Orphaned PENDING orders with no order items can never be reconciled -  | OPEN |
| F-017 | P1 | distributed-systems | No DLT consumer or replay path exists - a message that exhausts its 3  | OPEN |
| F-018 | P1 | distributed-systems | auto-offset-reset=latest in every Kafka consumer service: events publi | VERIFIED |
| F-019 | P0 | correctness | The payment-captured confirm gate and the cancel-refund trigger are bo | VERIFIED |
| F-020 | P1 | security | Service-to-service client secret defaults to 'changeme' in order-servi | VERIFIED |
| F-021 | P1 | correctness | A partial Stripe refund is recorded as a full refund: the webhook read | OPEN |
| F-022 | P2 | correctness | Payment create idempotency replays without validating that the request | OPEN |
| F-023 | P2 | correctness | The confirm-path payment gate inspects only the first page of payments | OPEN |
| F-024 | P2 | security | Webhook signature verifier returns HTTP 500 (not 401) for a malformed  | VERIFIED |
| F-025 | P2 | correctness | Admin cancel of a SHIPPED order marks it CANCELLED with no refund and  | OPEN |
| F-026 | P2 | correctness | Reservation release during compensation is best-effort and the failure | OPEN |
| F-027 | P2 | performance | cancelOrder holds a database transaction open across several remote HT | OPEN |
| F-028 | P2 | security | mergeCart lets any authenticated user claim any guest cart by guessing | OPEN |
| F-029 | P2 | distributed-systems | Consumer group ids are inconsistent: one order-service listener relies | OPEN |
| F-030 | P2 | correctness | Order tracking is unauthenticated and gated only by the order phone nu | OPEN |
| F-031 | P1 | security | Client-IP trust model is not fail-safe: with the shipped trusted-proxy | VERIFIED |
| F-032 | P1 | security | The gateway performs no JWT audience validation - a token minted for a | VERIFIED |
| F-033 | P1 | security | Gateway CORS combines allowCredentials=true with the shipped wildcard  | VERIFIED |
| F-034 | P2 | performance | The edge rate limiter uses in-process bucket4j buckets, so the effecti | OPEN |
| F-035 | P2 | security | The backoffice admin IP allowlist is INACTIVE by default in every ship | OPEN |
| F-036 | P3 | deployment | .env.prod.example omits CORS_ALLOWED_ORIGIN_PATTERNS, which the gatewa | OPEN |
| F-037 | P2 | correctness | tax-service soft-delete contradicts its own test - the module's build  | VERIFIED |
| F-039 | P0 | correctness | The JVM default timezone is sent as the PostgreSQL 'TimeZone' startup  | VERIFIED |
| F-040 | P1 | security | The provisioned Keycloak realm issues tokens with NO 'aud' claim - the | VERIFIED |
| F-041 | P1 | security | The SERVICE realm role is a fleet-wide super-privilege: any service cl | OPEN |
| F-042 | P1 | testing | The media-service integration suite referenced the non-existent Docker | VERIFIED |
| F-043 | P2 | testing | Shared Testcontainers databases are polluted across test classes, maki | VERIFIED |
| F-044 | P1 | correctness | coupon_usage_reservation has no version column: the TTL sweep can reve | VERIFIED |
| F-045 | P3 | distributed-systems | The promotion TTL sweep emitted no promotion.released.v1 event, unlike | VERIFIED |
| F-046 | P2 | correctness | ReservationRetryServiceImpl swallowed the interrupt flag and kept retr | VERIFIED |
| F-047 | P2 | correctness | A concurrent duplicate coupon reserve surfaced a raw DataIntegrityViol | VERIFIED |
| F-048 | P3 | correctness | Campaign update silently collapses every non-ACTIVE requested status t | OPEN |
| F-049 | P3 | correctness | CampaignServiceImpl.delete uses the strict orElseThrow() attribution w | OPEN |
| F-050 | P2 | correctness | Registration uniqueness check is a TOCTOU: two concurrent sign-ups wit | OPEN |
| F-051 | P1 | correctness | A password change did not revoke the sessions established with the old | VERIFIED |
| F-052 | P1 | correctness | A password reset did not revoke the sessions established with the comp | VERIFIED |
| F-053 | P2 | correctness | The password reset token has no version column: a reset token can be r | OPEN |
| F-054 | P2 | correctness | The password reset email is never sent - the only PasswordResetEmailSe | OPEN |
| F-055 | P1 | correctness | A self-registration naming only privileged roles created an account wi | VERIFIED |
| F-056 | P3 | security | The local role catalogue (USER, PM, ADMIN) does not match the provisio | OPEN |
| F-057 | P3 | security | forgotPassword logs the email address of a non-existent account | OPEN |
| F-058 | P1 | distributed-systems | product-service's outbox relay burned the whole retry budget inside on | VERIFIED |
| F-059 | P2 | database | product-service and rating-service had no outbox retention scheduler - | VERIFIED |
| F-060 | P2 | correctness | product-service soft-deletes a product without checking for active inv | OPEN |
| F-061 | P3 | correctness | product-service soft-deletes a variant with a hard-coded 'system' acto | OPEN |
| F-062 | P2 | performance | The media upload path amplifies memory to hundreds of megabytes for a  | OPEN |
| F-063 | P2 | correctness | Deleting a media asset does not check whether a live product still ref | OPEN |
| F-064 | P3 | documentation | The 'six WebP variants' claim is inaccurate: the renderer produces six | OPEN |
| F-065 | P1 | correctness | Hiding the only visible rating of a product crashed the moderation tra | VERIFIED |
| F-066 | P2 | performance | Remote calls are made inside database transactions in three more servi | OPEN |
| F-067 | P1 | correctness | The GHN and GHTK carrier adapters never contact the carrier - they fab | OPEN |
| F-068 | P2 | correctness | A shipment that the carrier never confirmed is auto-delivered after se | OPEN |
| F-069 | P2 | security | The admin IP allowlist uses a prefix match without a path boundary, so | OPEN |
