# Outstanding Tasks

Generated: 2026-11-10T10:05:42

| State | Count |
|---|---|
| READY | 41 |
| VERIFIED | 27 |
| Priority | Count |
|---|---|
| P0 | 2 |
| P1 | 23 |
| P2 | 31 |
| P3 | 12 |

Mandatory open tasks: **7**

| ID | Priority | State | Title |
|---|---|---|---|
| TSK-019 | P0 | VERIFIED | The payment-captured confirm gate and the cancel-refund trigger are bo |
| TSK-039 | P0 | VERIFIED | The JVM default timezone is sent as the PostgreSQL 'TimeZone' startup  |
| TSK-001 | P1 | READY | Kafka decode failures are silently dropped: no DLT, no retry, no opera |
| TSK-004 | P1 | READY | OAuth2 ROPC grant used for end-user login and for the Keycloak master- |
| TSK-005 | P1 | VERIFIED | JWT audience validation is implemented but never configured - a token  |
| TSK-010 | P1 | VERIFIED | Stock columns have no non-negative invariant: reserved_quantity/availa |
| TSK-011 | P1 | VERIFIED | Reservation row has no version column: the expiry sweep can overwrite  |
| TSK-016 | P1 | READY | Orphaned PENDING orders with no order items can never be reconciled -  |
| TSK-017 | P1 | READY | No DLT consumer or replay path exists - a message that exhausts its 3  |
| TSK-018 | P1 | VERIFIED | auto-offset-reset=latest in every Kafka consumer service: events publi |
| TSK-020 | P1 | VERIFIED | Service-to-service client secret defaults to 'changeme' in order-servi |
| TSK-021 | P1 | READY | A partial Stripe refund is recorded as a full refund: the webhook read |
| TSK-031 | P1 | VERIFIED | Client-IP trust model is not fail-safe: with the shipped trusted-proxy |
| TSK-032 | P1 | VERIFIED | The gateway performs no JWT audience validation - a token minted for a |
| TSK-033 | P1 | VERIFIED | Gateway CORS combines allowCredentials=true with the shipped wildcard  |
| TSK-040 | P1 | VERIFIED | The provisioned Keycloak realm issues tokens with NO 'aud' claim - the |
| TSK-041 | P1 | READY | The SERVICE realm role is a fleet-wide super-privilege: any service cl |
| TSK-042 | P1 | VERIFIED | The media-service integration suite referenced the non-existent Docker |
| TSK-044 | P1 | VERIFIED | coupon_usage_reservation has no version column: the TTL sweep can reve |
| TSK-051 | P1 | VERIFIED | A password change did not revoke the sessions established with the old |
| TSK-052 | P1 | VERIFIED | A password reset did not revoke the sessions established with the comp |
| TSK-055 | P1 | VERIFIED | A self-registration naming only privileged roles created an account wi |
| TSK-058 | P1 | VERIFIED | product-service's outbox relay burned the whole retry budget inside on |
| TSK-065 | P1 | VERIFIED | Hiding the only visible rating of a product crashed the moderation tra |
| TSK-067 | P1 | READY | The GHN and GHTK carrier adapters never contact the carrier - they fab |
| TSK-002 | P2 | READY | Four published lifecycle topics have no consumer: inventory, promotion |
| TSK-006 | P2 | READY | Shared default config ships default Keycloak master-realm admin creden |
| TSK-009 | P2 | READY | server.error.include-message=always exposes internal exception text to |
| TSK-012 | P2 | VERIFIED | Expiry sweep releases stock without emitting the inventory.released.v1 |
| TSK-013 | P2 | READY | Outbox relay holds a PESSIMISTIC_WRITE row lock and an open DB transac |
| TSK-014 | P2 | VERIFIED | Reservation retry loop swallows the interrupt flag and keeps retrying  |
| TSK-022 | P2 | READY | Payment create idempotency replays without validating that the request |
| TSK-023 | P2 | READY | The confirm-path payment gate inspects only the first page of payments |
| TSK-024 | P2 | VERIFIED | Webhook signature verifier returns HTTP 500 (not 401) for a malformed  |
| TSK-025 | P2 | READY | Admin cancel of a SHIPPED order marks it CANCELLED with no refund and  |
| TSK-026 | P2 | READY | Reservation release during compensation is best-effort and the failure |
| TSK-027 | P2 | READY | cancelOrder holds a database transaction open across several remote HT |
| TSK-028 | P2 | READY | mergeCart lets any authenticated user claim any guest cart by guessing |
| TSK-029 | P2 | READY | Consumer group ids are inconsistent: one order-service listener relies |
| TSK-030 | P2 | READY | Order tracking is unauthenticated and gated only by the order phone nu |
| TSK-034 | P2 | READY | The edge rate limiter uses in-process bucket4j buckets, so the effecti |
| TSK-035 | P2 | READY | The backoffice admin IP allowlist is INACTIVE by default in every ship |
| TSK-037 | P2 | VERIFIED | tax-service soft-delete contradicts its own test - the module's build  |
| TSK-043 | P2 | VERIFIED | Shared Testcontainers databases are polluted across test classes, maki |
| TSK-046 | P2 | VERIFIED | ReservationRetryServiceImpl swallowed the interrupt flag and kept retr |
| TSK-047 | P2 | VERIFIED | A concurrent duplicate coupon reserve surfaced a raw DataIntegrityViol |
| TSK-050 | P2 | READY | Registration uniqueness check is a TOCTOU: two concurrent sign-ups wit |
| TSK-053 | P2 | READY | The password reset token has no version column: a reset token can be r |
| TSK-054 | P2 | READY | The password reset email is never sent - the only PasswordResetEmailSe |
| TSK-059 | P2 | VERIFIED | product-service and rating-service had no outbox retention scheduler - |
| TSK-060 | P2 | READY | product-service soft-deletes a product without checking for active inv |
| TSK-062 | P2 | READY | The media upload path amplifies memory to hundreds of megabytes for a  |
| TSK-063 | P2 | READY | Deleting a media asset does not check whether a live product still ref |
| TSK-066 | P2 | READY | Remote calls are made inside database transactions in three more servi |
| TSK-068 | P2 | READY | A shipment that the carrier never confirmed is auto-delivered after se |
| TSK-069 | P2 | READY | The admin IP allowlist uses a prefix match without a path boundary, so |
| TSK-003 | P3 | READY | Dead legacy topic-name configuration in KafkaProperties.Topics |
| TSK-007 | P3 | READY | Contradictory bean-validation constraints on SecurityProperties.Cors.m |
| TSK-008 | P3 | READY | Object-storage access/secret key default to rustfsadmin in the shared  |
| TSK-015 | P3 | READY | reservations table has no index on order_id and no foreign key to inve |
| TSK-036 | P3 | READY | .env.prod.example omits CORS_ALLOWED_ORIGIN_PATTERNS, which the gatewa |
| TSK-045 | P3 | VERIFIED | The promotion TTL sweep emitted no promotion.released.v1 event, unlike |
| TSK-048 | P3 | READY | Campaign update silently collapses every non-ACTIVE requested status t |
| TSK-049 | P3 | READY | CampaignServiceImpl.delete uses the strict orElseThrow() attribution w |
| TSK-056 | P3 | READY | The local role catalogue (USER, PM, ADMIN) does not match the provisio |
| TSK-057 | P3 | READY | forgotPassword logs the email address of a non-existent account |
| TSK-061 | P3 | READY | product-service soft-deletes a variant with a hard-coded 'system' acto |
| TSK-064 | P3 | READY | The 'six WebP variants' claim is inaccurate: the renderer produces six |
