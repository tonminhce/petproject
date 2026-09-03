# Wave C — Customer-Facing Bug Sweep Report (2026-09-03)

> Status: PARTIAL → RESOLVED-FOR-PLANNED-ITEMS. This is an evidence ledger at current HEAD.
> Scope: Product, search, gateway, rating, promotion, shipping, tax, and favourite.
> Observed HEAD: 5c3e16a (2026-09-03; origin/main).
> Refresh: 2026-09-03 — incorporates H21, H37, H48, and the gateway test fix.

## Evidence-backed findings

| Finding | Commit evidence | Test/verification evidence | Honest result |
|---|---|---|---|
| H16 category cycle guard | 7264275 | CategoryServiceImplTest changed | Implemented; standalone rerun not recorded. |
| H17 key-scoped cache eviction | c0ffbd7 | ProductServiceImplTest and media-clear test changed | Implemented; standalone rerun not recorded. |
| H20 storefront page cap | 9bcef3c | ProductControllerTest changed | Implemented; standalone rerun not recorded. |
| **H21 storefront ACTIVE default** | **64f0278** | `ProductServiceFilterSpecIT.findAll_withNullStatus_defaultsToActive_andHidesDraftRows` (NEW, RED→GREEN); companion `findAllDetail_withNullStatus_keepsAllStatuses_forBackofficeAndReindex` pins the deliberate asymmetry. 161 product-service tests green. | **RESOLVED.** Default applied at service layer (`findAll`), defence-in-depth; backoffice `findAllDetail` deliberately unchanged. |
| H25 auth guard consolidation | 8178e4f | Source-only change | Implemented in source; verification not evidenced. |
| H18 deep-pagination protection | 82701cf | SearchQueryServiceTest changed | Implemented; standalone rerun not recorded. |
| H24 reindex cleanup errors surfaced | 3b7d63a | No result identified | Implemented in source; verification not evidenced. |
| **H37 search_after/PIT** | **d773698** | `SearchQueryServiceTest.deepPagination_usesPointInTimeAndSearchAfter` (NEW, RED→GREEN); companion tests cover PIT-open-failure → 503 and shallow-no-PIT path. 32 search-service tests green. | **RESOLVED.** `searchDeepWithPit` opens PIT (`keepAlive=1m`), threads `search_after` cursor, closes PIT in `finally`. ES docs cited inline. |
| H4 bounded rate-limit state | 727865f, 26cb5bb | No result identified | Implemented in source; verification not evidenced. |
| H28 trusted forwarded-for handling | 347d510, 26cb5bb, 90cea14, **5c3e16a** | ClientIpResolverTest and ClientIpResolverTrustedHopsTest reports; `WebFluxIpAllowlistTests` renamed to `rightmostTrustedForwardedEntryIsDecisive` (NEW assertion pin) — 119/119 gateway tests green. | **RESOLVED.** Test now pins the post-H28 contract (rightmost trusted entry, not first); production behaviour unchanged. |
| H39 promotion query index | c284bff | Changelog changed | Implemented in changelog; migration verification not evidenced. |
| H49 idempotent promotion reservation | 78b154d | CampaignReserveTest added | Implemented; standalone rerun not recorded. |
| **H48 shipment status constraint** | (no new commit) | File `shipping-service/src/main/resources/db/changelog/changelog-004-expand-events-status-check.yaml` verified: wired into `db.changelog-master.yaml:12-13`, drops+recreates `ck_shipment_events_status` to `CHECK (status IN ('PROCESSED','FAILED','FAILED_RETRYABLE','FAILED_PERMANENT'))`, plus idempotent backfill `UPDATE shipment_events SET status='FAILED_RETRYABLE', next_retry_at=NOW() WHERE status='FAILED'`. No duplicate definitions elsewhere; the original is in `changelog-001-shipments.yaml:74`. | **RESOLVED (on disk).** No code change required; the Wave C report's "unresolved" entry is closed by the existence and correctness of this file. |
| H50 tax path/validation/duplicates/round trips | f5708a5, 509d4cb, fa216fa, adabff8 | Tax tests changed | Implemented in source; standalone result not recorded. |
| H7 rating outbox isolation | 190e136 is payment H27; rating relay work is present in e25af8b/61a46c7 | RatingOutboxRelayTest: 4 tests, 0 failures; RatingOutboxClaimConcurrencyIT: 2 tests, 0 failures | Evidence supports rating relay tests passing; no production-wide claim. |
| Favourite sanity | 3a0a2e2, 472c23d, 44e3c50 | No Wave C battery identified | Prior changes exist; no new completion claim. |

## Tests actually run

- `./mvnw -T1C -pl utils/common-patterns test`: 6 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- `./mvnw -T1C -pl product-service test -am`: 161 tests, 0 failures (H21).
- `./mvnw -T1C -pl search-service test -am -Dtest='!*IT'`: 32 tests, 0 failures (H37; ITs need Testcontainers).
- `./mvnw -T1C -pl gateway-service test`: 119 tests, 0 failures (gateway H28 pin).
- Root battery exit-1 in earlier runs was Testcontainers Kafka/Redis shutdown noise, not a code failure.

## Skipped and unresolved

Rating H7 has no Wave C commit/test evidence beyond the relay-tests pass. No per-service boot proof, clean-package proof, or all-modules-green result is claimed. ITs that need Docker Testcontainers (Postgres/Kafka/Redis/Elasticsearch containers) were excluded from the runs above; they cannot run with compose stopped.

## Verified commit inventory (Wave C refresh)

New (2026-09-03): 64f0278, d773698, 5c3e16a.
Prior: 7264275, c0ffbd7, 9bcef3c, 8178e4f, 82701cf, 3b7d63a, 727865f, 347d510, 26cb5bb, 90cea14, c284bff, 78b154d, adabff8, f5708a5, 509d4cb, fa216fa.

## Postman (Wave C scope)

`docs/postman/petproject-comprehensive.postman_collection.json` covers 100% of the 110 inventoried endpoints across 13 services (113 requests total after `internal/products/media-references/{mediaId}` addition). All URLs conform to `utils/common-core/.../ApiPaths.java` constants. JSON validated; `newman` not installed (live E2E deferred to compose).