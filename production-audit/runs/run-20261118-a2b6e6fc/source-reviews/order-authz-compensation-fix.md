# SEC-02 and OI-02 — order-service remediation report

Run: `run-20261118-a2b6e6fc`
Branch: `audit/production-hardening-run-20261118-a2b6e6fc`
Scope: `order-service/` only (no shared library, no other service, no harness, no root `pom.xml` touched)

Findings closed:

| Id | Title | Severity |
|---|---|---|
| SEC-02 | Cross-service IDOR: `verify-purchase` accepts an arbitrary `userId` with no principal cross-check | HIGH |
| OI-02 | A failed `releaseCommitted` in the confirm-compensation path is dropped: no ledger, no retry, stock permanently consumed | HIGH |

---

## 1. SEC-02 — delegated order read authorisation

### 1.1 The exact authorisation rule

`GET /api/v1/orders/verify-purchase?userId=&productId=` (route, query shape and `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")` unchanged) now runs through
`DelegatedOrderReadAuthorizer.authorize(requestedUserId, actingUserIdHeader)` before any row is read. The rule, in evaluation order:

1. **No established principal → reject.** An empty, anonymous or unauthenticated security context raises `401 ERR-0401`. The guard fails closed; it never returns "no objection".
2. **Administrator → allow, any user.** A caller holding the `ADMIN` role (realm role in `realm_access` **or** the `ADMIN` / `ROLE_ADMIN` granted authority) may read any user's delivered items. The `userId` query parameter is honoured as-is. This is an explicit role check, not a default.
3. **Anything that is neither administrator nor service → reject (`403 ERR-0403`).**
4. **Service-role caller → the delegated read must be bound:**
   a. the token must carry an **authorised-party identity**: `azp`, read through `utils/common-security`'s `JwtClaimExtractor.authorizedParty(jwt)`. A service token without `azp` is rejected — the platform identity is the token, never a request parameter;
   b. the caller must present the user it acts for in the **`X-Acting-User-Id`** header (constant `DelegatedOrderReadAuthorizer.ACTING_USER_HEADER`), and that value must be **equal** to the `userId` query parameter;
   c. the platform must have **bound that authorised party to that user**: a row `(client_id = azp, user_id = requested user)` must exist in the new `service_user_bindings` registry. No binding ⇒ `403`.

Consequences that the tests prove:

* A service-role caller cannot read another user's delivered items by putting a different `userId` in the query string (the header no longer matches ⇒ reject).
* A service-role caller cannot escape by changing the header too (the binding for `(azp, other user)` does not exist ⇒ reject).
* A service-role caller whose token has no `azp` is rejected.
* A service client that is bound to user A cannot read user B, even though the whole fleet shares one `azp` allow-list (`SEC-04`).

### 1.2 How the authorised party is bound to a user

The binding is **platform state, not request state**:

* New table `service_user_bindings` (`id`, `client_id`, `user_id`, `created_at`, `created_by`) with `UNIQUE (client_id, user_id)` — Liquibase changeset `changelog-011-service-user-bindings.yaml` (additive, registered in `db.changelog-master.yaml`).
* It is written **only** by an administrator through the new backoffice route `POST|DELETE|GET /api/v1/backoffice/order-service-user-bindings` (`BackofficeServiceUserBindingController`, `@PreAuthorize("hasRole('ADMIN')")`). A service client can never create or widen its own binding.
* `ServiceUserBindingService` is the write path (idempotent bind, unbind, paged read); `DelegatedOrderReadAuthorizer` is the read path (`existsByClientIdAndUserId`).

**Why a registry and not a header alone:** the header alone would be attacker-controlled (any service client could name any user). The registry is what makes the header trustworthy: the platform decides which client may act for which user, and the request only selects *which* of the platform-approved pairs to use.

**Follow-up requirement (platform binding not fully in place — stated plainly):** the registry in `order-service` is the platform's binding store *for this service*, but the platform does not yet mint a per-user delegation token (Keycloak token exchange with an `act` claim, or a per-client `aud`/scope mapper). Until that exists, a service client still has to be registered row-by-row for every user it acts for, which does not scale to the whole customer base. Recommended platform work (outside this write scope): issue a delegated token whose `azp` identifies the acting client **and** which carries the acting user as a signed claim (RFC 8693 `act`/token exchange), so that the binding can be derived from the token itself and the registry can be reduced to an allow-list of client→scope. This is recorded as an open platform requirement, not silently ignored.

`ServiceCallerGuard` was deliberately **not** used: it expresses a *client allow-list* (`requireCaller("rating-service")`), which cannot express a per-user binding, and the registry is strictly narrower than any client allow-list. The `azp` extraction goes through `JwtClaimExtractor.authorizedParty`, the API the audit pointed at.

---

## 2. OI-02 — compensation ledger and sweeper

### 2.1 What was dropped and what now happens

`OrderCommitCoordinator.compensateInReverse` used to swallow a failed `releaseCommitted` with a log line ("reconciliation owns it") — but `OrderReconciliationScheduler` only *reads* reservation states, so the stock stayed consumed forever. Now:

* Every failed compensation release in the confirm path is recorded in the **existing** `failed_compensations` ledger (`FailedCompensationRepository`, `changelog-010-failed-compensations.yaml`) — no second mechanism was created.
* New ledger **kinds** (the column is `varchar(32)`): `RESERVATION_RELEASE_COMMITTED` (inventory, 29 chars) and `PROMOTION_RELEASE_COMMITTED` (27 chars) next to the existing `RESERVATION_RELEASE`.
* New ledger **statuses**: `PROCESSING` (claimed by a sweeper) and `EXHAUSTED` (retry budget spent, manual action required) next to `PENDING` / `RESOLVED`.
* `OrderServiceImpl.releaseAllCommittedReservationsById` (admin cancel of a confirmed order) previously recorded a *committed* release under the plain `RESERVATION_RELEASE` kind, so the sweeper would have replayed the wrong operation; it now records `RESERVATION_RELEASE_COMMITTED`.

### 2.2 The sweeper

`CompensationFailureService.retryPending()` remains the single `@Scheduled` entry point (`order.compensation.retry-millis`, default 30 s) and now works as a **claim → dispatch → settle** pipeline, with the HTTP call **outside** any transaction (the previous version held a transaction open across up to 50 HTTP calls):

1. **Select** due rows: `status IN ('PENDING','PROCESSING') AND (next_attempt_at IS NULL OR next_attempt_at <= now)`, oldest first, batch `order.compensation.batch-size` (default 50).
2. **Claim** each candidate with a single conditional bulk update:
   `UPDATE failed_compensations SET status='PROCESSING', next_attempt_at = now + lease WHERE id = :id AND status IN ('PENDING','PROCESSING') AND (next_attempt_at IS NULL OR next_attempt_at <= now)`.
   The update returns 1 for exactly one replica and 0 for the others; a replica that loses the race skips the row entirely.
3. **Dispatch** by kind (the kind now decides the remote operation):
   * `RESERVATION_RELEASE` → `POST /inventory/reservations/{id}/release`
   * `RESERVATION_RELEASE_COMMITTED` → `POST /inventory/reservations/{id}/release-committed`
   * `PROMOTION_RELEASE_COMMITTED` → `POST /promotions/reservations/{id}/release-committed`
   * any other kind → the entry is marked `EXHAUSTED` immediately (it can never be dispatched).
4. **Settle** in a fresh transaction:
   * success → `RESOLVED` + `resolved_at`, `next_attempt_at = null`;
   * failure → `attempts = attempts + 1`, `last_error`, `next_attempt_at = now + backoff` (`order.compensation.retry-backoff-seconds`, default 60 s) and the row goes back to `PENDING`;
   * when `attempts` reaches the bound → status `EXHAUSTED`, `next_attempt_at = null`, an `ERROR` log line and a Prometheus counter `order.compensation.exhausted{kind=...}` — i.e. the entry is **surfaced for manual action and never retried again**.

### 2.3 Bounded attempt count

`order.compensation.max-attempts` (default **5**). A ledger entry is attempted at most that many times; the failure that reaches the bound flips it to `EXHAUSTED` and the sweeper's select (`PENDING`/`PROCESSING` only) never picks it up again. The bound is configurable per deployment via `ORDER_COMPENSATION_MAX_ATTEMPTS`.

### 2.4 Multi-replica safety argument

* **Claim, not read.** The sweeper does not decide "this row is mine" by reading a status; it decides by *winning a conditional UPDATE*. In PostgreSQL's READ COMMITTED isolation, the loser of the race blocks on the row lock, re-evaluates the predicate after the winner commits, finds `status = 'PROCESSING'` and `next_attempt_at` in the future, and updates **0 rows**. Exactly one replica per row performs the release.
* **Lease.** The claim sets `next_attempt_at = now + order.compensation.claim-lease-seconds` (default 120 s). A replica that dies mid-flight therefore does not block the entry forever: the lease expires and another replica re-claims it. The window is a crash window, not a concurrency window — and it is safe because the remote `release`/`release-committed` operations are idempotent on the inventory side (`InventoryServiceImpl` early-returns on an already-released reservation).
* **No transaction spans the HTTP call.** The claim commits before the release, and the settle commits after it, so a slow inventory call never holds a database lock.
* **One open ledger row per (reservation, kind).** New changeset `changelog-012-failed-compensation-sweeper.yaml` adds a partial unique index `uc_failed_compensations_open ON failed_compensations (reservation_id, kind) WHERE status IN ('PENDING','PROCESSING')` (after a de-duplicating `DELETE`, so the migration is safe on an existing database) plus a scan index. Two concurrent `record()` calls therefore cannot create two open entries for the same reservation and kind, so even a duplicated ledger cannot double-release. `record()` tolerates the losing race (the insert is caught as `DataIntegrityViolationException` and logged).

**Residual, stated plainly:** the guarantee is "no two concurrent sweepers release the same entry". It is *not* exactly-once across a crash between the successful remote release and the `RESOLVED` write — that entry is replayed after the lease expires. The replay is a no-op at the inventory side because the remote release is idempotent.

---

## 3. Files created or modified

All paths are relative to the repository root; every one of them is under `order-service/`.

### Created — SEC-02

| File | Description |
|---|---|
| `order-service/src/main/java/com/shop/orderservice/entity/ServiceUserBinding.java` | JPA entity for the `service_user_bindings` registry (client ↔ user binding). |
| `order-service/src/main/java/com/shop/orderservice/repository/ServiceUserBindingRepository.java` | Repository: `existsByClientIdAndUserId`, `findByClientIdAndUserId`, paged `findByClientId`. |
| `order-service/src/main/java/com/shop/orderservice/service/ServiceUserBindingService.java` | Write/read service for the registry: idempotent `bind`, `unbind`, `isBound`, paged `find`. |
| `order-service/src/main/java/com/shop/orderservice/security/DelegatedOrderReadAuthorizer.java` | The authorisation rule for the delegated order read (fails closed, `azp` as the authorised party, admin bypass, binding check). |
| `order-service/src/main/java/com/shop/orderservice/controller/BackofficeServiceUserBindingController.java` | Admin-only backoffice route that manages the registry (`POST`/`DELETE`/`GET /api/v1/backoffice/order-service-user-bindings`). |
| `order-service/src/main/java/com/shop/orderservice/dto/request/ServiceUserBindingRequest.java` | Validated request record `{clientId, userId}`. |
| `order-service/src/main/java/com/shop/orderservice/dto/response/ServiceUserBindingResponse.java` | Response record for a binding row. |
| `order-service/src/main/resources/db/changelog/changelog-011-service-user-bindings.yaml` | Additive Liquibase changeset: creates `service_user_bindings` + unique `(client_id, user_id)` + index on `user_id`. |

### Created — OI-02

| File | Description |
|---|---|
| `order-service/src/main/java/com/shop/orderservice/config/CompensationProperties.java` | `@ConfigurationProperties(order.compensation)`: backoff, claim lease, max attempts, batch size (with defaults). |
| `order-service/src/main/resources/db/changelog/changelog-012-failed-compensation-sweeper.yaml` | Additive Liquibase changeset: de-duplicate open ledger rows, partial unique index on open `(reservation_id, kind)`, scan index on `created_at` for open rows. |

### Modified — SEC-02

| File | Description |
|---|---|
| `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` | `verifyPurchase` now takes the optional `X-Acting-User-Id` header and calls `delegatedOrderReadAuthorizer.authorize(userId, actingUserId)` before reading any row; the authorizer is injected. Route and query shape unchanged. |

### Modified — OI-02

| File | Description |
|---|---|
| `order-service/src/main/java/com/shop/orderservice/entity/FailedCompensation.java` | Added statuses `PROCESSING`, `EXHAUSTED` and kinds `RESERVATION_RELEASE_COMMITTED`, `PROMOTION_RELEASE_COMMITTED`. |
| `order-service/src/main/java/com/shop/orderservice/repository/FailedCompensationRepository.java` | Added `existsByReservationIdAndKindAndStatusIn`, the paged `findDue` query and the atomic `claimForProcessing` conditional update; removed the now-unused `findTop50…` finder. |
| `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java` | Rewritten sweeper: claim → dispatch-by-kind → settle, bounded attempts, `EXHAUSTED` + metric, crash-safe `record()` that tolerates the duplicate-insert race, HTTP calls outside the transaction. |
| `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java` | A failed compensation release is now recorded in the ledger with the right kind (`RESERVATION_RELEASE_COMMITTED` / `PROMOTION_RELEASE_COMMITTED`) instead of being swallowed; `CompensationFailureService` injected. |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java` | The admin-cancel path records a committed release under `RESERVATION_RELEASE_COMMITTED` instead of `RESERVATION_RELEASE`. |
| `order-service/src/main/resources/application.yml` | New `order.compensation.*` keys (`retry-millis`, `retry-backoff-seconds`, `claim-lease-seconds`, `max-attempts`, `batch-size`). |
| `order-service/src/main/resources/db/changelog/db.changelog-master.yaml` | Registers `changelog-011` and `changelog-012` (no existing changeset was modified). |

### Modified / created — tests

| File | Description |
|---|---|
| `order-service/src/test/java/com/shop/orderservice/controller/DelegatedOrderReadAuthorizationTest.java` | **New** — the SEC-02 scenarios (admin read, unbound service rejected, bound service allowed, query-only change rejected, header change rejected, missing acting user, token without `azp`, client bound to another client). |
| `order-service/src/test/java/com/shop/orderservice/service/ConfirmCompensationLedgerIT.java` | **New** — the OI-02 scenarios against a real Postgres + WireMock: failed release recorded, sweeper releases on a later pass, sweeper stops at the bound, two concurrent sweepers release exactly once. |
| `order-service/src/test/java/com/shop/orderservice/service/ServiceUserBindingIT.java` | **New** — the registry is durable: bind persists, bind is idempotent, bound client allowed / other user rejected, unbound client rejected, unbind revokes. |
| `order-service/src/test/java/com/shop/orderservice/controller/VerifyPurchaseEndpointTest.java` | **Changed** — the existing service-role cases encoded the buggy behaviour (a service client reading any user's items with no binding). They now present a bound acting user; the case was renamed `verifyPurchase_serviceRoleWithABoundActingUser_…`. |
| `order-service/src/test/java/com/shop/orderservice/service/OrderCommitCoordinatorTest.java` | **Changed** — the case that asserted the rollback failure was *swallowed* was renamed to `commit_rollbackFailure_recordedInTheLedger_counterIncremented_originalRethrown` and now asserts the ledger write; three cases added (inventory kind, promotion kind, no ledger entry on a successful compensation). |
| `order-service/src/test/java/com/shop/orderservice/controller/OrderControllerTest.java` | **Changed** — imports the authorizer and mocks the binding repository so the `@WebMvcTest` context loads with the new controller dependency (no behavioural change to the assertions). |

---

## 4. Commands executed (exact, with exit codes and test counts)

| # | Command | Exit | Result |
|---|---|---|---|
| 1 | `./mvnw -B -ntp -pl order-service -am test` | **0** | **BUILD SUCCESS** — order-service `Tests run: 309, Failures: 0, Errors: 0, Skipped: 0`; upstream modules: common-core 16, common-security 27, common-logging 32, common-keycloak 26, common-kafka 49, common-storage 6, common-spring 36. Total time 05:59 min (re-run after the last cleanup, exit 0). |
| 2 | `./mvnw -B -ntp -o -pl order-service test` (order-service only, dependencies installed with `-DskipTests`) | 0 | `Tests run: 309, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS (re-run after the last cleanup). |
| 3 | `./mvnw -B -ntp -o -pl order-service -am test -Dtest='OrderCommitCoordinatorTest,CompensationFailureLedgerIT,ConfirmCompensationLedgerIT,ServiceUserBindingIT' -Dsurefire.failIfNoSpecifiedTests=false` | 0 | `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`. |
| 4 | `./mvnw -B -ntp -o -pl order-service -am test -Dtest='OrderControllerTest,VerifyPurchaseEndpointTest,DelegatedOrderReadAuthorizationTest' -Dsurefire.failIfNoSpecifiedTests=false` | 0 | `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`. |
| 5 | `./mvnw -B -ntp -o -T1C -pl order-service validate` (Checkstyle quality gate) | 0 | BUILD SUCCESS, no violations. |
| 6 | `./mvnw -B -ntp -o -pl order-service -am compile -DskipTests` / `… test-compile` | 0 | BUILD SUCCESS. |

Test counts before the change (baseline, same module): the module had 289 tests; it now runs **309** (20 new test methods: 8 + 5 + 4 in the three new test classes and 3 added to `OrderCommitCoordinatorTest`; two existing cases were renamed, none was removed or weakened).

Note on the first execution of command 1: it failed at `common-kafka` (3 failures in `BaseKafkaListenerConfigDeadLetterRecovererTest` / `BaseKafkaConsumerSchemaEvolutionTest`) while another agent was mid-edit in `utils/common-kafka` — outside this write scope. The same command was re-run after that module was green and passed (exit 0, table row 1). A later standalone run also showed a transient `class path resource …/AbstractOrderServiceIT.class cannot be opened` error caused by a concurrent `mvn` in the same working tree recompiling `target/test-classes`; the re-run was clean.

---

## 5. New tests (names)

### SEC-02 — `DelegatedOrderReadAuthorizationTest` (new, `@WebMvcTest`)

* `anAdministratorMayReadAnotherUsersDeliveredItems` — happy path: an administrator reads another user's delivered items (`200`, the victim's item payload is returned, the binding registry is never consulted).
* `aServiceRoleCallerWithoutABindingIsRejected` — edge case: a service-role caller requesting another user's delivered items is rejected (`403`, the order service is never called).
* `aServiceRoleCallerWithABoundAuthorisedPartyMayReadTheBoundUser` — edge case: the same request with a bound authorised-party identity succeeds (`200`).
* `aServiceRoleCallerCannotChangeTheTargetByChangingOnlyTheQueryParameter` — edge case: changing only the `userId` query parameter is rejected (`403`).
* `aServiceRoleCallerCannotChangeTheTargetByChangingTheActingUserEither` — edge case: changing the header as well is still rejected (no binding for the other user).
* `aServiceRoleCallerWithoutAnActingUserIsRejected` — edge case: no `X-Acting-User-Id` ⇒ `403`.
* `aServiceTokenWithoutAnAuthorisedPartyIsRejected` — edge case: a service token without `azp` ⇒ `403`.
* `aServiceRoleCallerBoundToAnotherClientCannotReadTheUser` — edge case: a different `azp` cannot use another client's binding.

`ServiceUserBindingIT` (new, real Postgres) — `aRegisteredBindingIsPersisted`, `aBindingIsIdempotent`, `theBoundClientMayReadTheBoundUserAndNobodyElse`, `anUnboundClientMayNotReadAnyUser`, `aRemovedBindingStopsTheDelegatedRead`.

### OI-02 — `ConfirmCompensationLedgerIT` (new, real Postgres + WireMock)

* `aFailedStockReleaseInTheConfirmCompensationIsRecordedInTheLedger` — happy/edge path: the confirm compensation's failed `release-committed` produces exactly one ledger row (`kind = RESERVATION_RELEASE_COMMITTED`, `status = PENDING`, `attempts = 0`, the right `orderId`, a non-null `next_attempt_at` and `last_error`); the order stays `PENDING` with `confirmed_at = null`; the order items still carry their reservation ids; the outbox holds exactly one `order.created.v1` and **no** `order.updated.v1` for the order.
* `theSweeperReleasesTheStockOnALaterPass` — happy path: a later sweeper pass issues exactly one `release-committed` (and **no** plain `release`) and the ledger row becomes `RESOLVED` with `resolved_at`.
* `theSweeperStopsAfterTheBoundedAttemptCount` — edge case: after `max-attempts` failing passes the entry is `EXHAUSTED` with `attempts = max-attempts`, `next_attempt_at = null` and the `order.compensation.exhausted` counter incremented; a further pass issues no further release.
* `twoConcurrentSweepersReleaseTheStockExactlyOnce` — **concurrency test**: two sweeper instances (the Spring singleton and a second, independently constructed replica sharing the same repository/transaction manager) run at the same time behind a `CyclicBarrier`; the inventory receives exactly **one** additional `release-committed` and the ledger row ends `RESOLVED`.

`OrderCommitCoordinatorTest` (modified) — `commit_rollbackFailure_recordedInTheLedger_counterIncremented_originalRethrown` (renamed from `commit_rollbackFailure_swallowed_counterIncremented_originalRethrown`), `commit_rollbackFailure_isRecordedInTheCompensationLedger`, `commit_promotionRollbackFailure_isRecordedInTheCompensationLedger`, `commit_successfulCompensation_leavesNoLedgerEntry`.

### Assertions on real persisted state

The new tests assert the **ledger rows** read back from Postgres (`failed_compensations`: kind, status, attempts, order id, reservation id, `next_attempt_at`, `resolved_at`, `last_error`), the **reservation state** as the order service sees it (the exact remote operation: `release-committed` for a `COMMITTED` reservation, and *never* the plain `release`), the **order state** (`orders.status = PENDING`, `confirmed_at = null`, `order_items.reservation_id`), and the **emitted events** (the `outbox_events` rows: one `order.created.v1`, no `order.updated.v1`).

---

## 6. Acceptance criteria — what could NOT be satisfied

1. **The platform-side delegation binding is not a platform-minted token.** The platform (Keycloak realm, gateway, `utils/common-security`) issues no per-user delegated token: no token-exchange grant, no `act` claim, no audience mapper, and the fleet-wide `azp` allow-list (`SEC-04`) is unchanged because `utils/common-security` and the gateway are outside this write scope. I implemented the binding the task sanctions — a server-side registry keyed by the token's `azp`, written only by an administrator — which satisfies the invariant (a service caller cannot read another user's items by changing the query parameter), but the **binding itself is still operator-managed per user**, which does not scale. Recorded as a follow-up platform requirement in §1.2.
2. **`rating-service` was not switched over.** The intended caller of `verify-purchase` (`rating-service/.../eligibility/EligibilityClient.java`) still sends only `userId`/`productId` and will now be rejected until it (a) presents `X-Acting-User-Id` and (b) has a binding row. `rating-service` is outside this write scope, so the caller-side change is not part of this delivery; the endpoint therefore fails **closed** for it in the meantime (the safe direction — the endpoint returns 403 rather than leaking). The new header is optional in the controller, so no request shape was broken.
3. **No e2e/contract coverage outside `order-service`.** The cross-service call path (`rating-service` → `order-service`) could not be exercised end-to-end because the caller lives in another module that another agent owns.
4. **The pre-existing, unrelated `azp` allow-list defect (`SEC-04`) is untouched** — narrowing `shop.security.expected-client-ids` per service is a root-configuration/other-service change and was out of scope. The binding registry is strictly narrower than the fleet-wide list, so the SEC-02 exposure does not depend on fixing `SEC-04`.
5. **Exactly-once is not guaranteed across a crash window.** If a sweeper dies between a successful remote release and the `RESOLVED` write, the entry is replayed after the claim lease expires. The remote release is idempotent, so the stock is not double-restored, but the HTTP call is repeated. A transactional outbox with the inventory side would be needed for a strict exactly-once; that crosses into `inventory-service`.
6. **The one-open-row unique index is a PostgreSQL partial index** expressed with a Liquibase `sql` change (the repo already uses `sql` changesets, e.g. `changelog-002`). It is therefore not portable to a non-PostgreSQL deployment; `order-service` is PostgreSQL-only in this fleet (`ddl-auto=validate`, Postgres driver), so this is consistent with the module.
7. **Concurrency of the *recording* path is protected by the partial unique index, not by a lock.** Two simultaneous `record()` calls for the same `(reservation, kind)` result in exactly one open ledger row; the loser logs and moves on (`record()` catches `DataIntegrityViolationException`).
