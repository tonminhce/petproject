# Wave C — Customer-Facing Bug Sweep Report (2026-09-03)

> Status: PARTIAL / REPORTING ONLY. Commits listed below are present on main; this report does not claim every planned item completed.
> Scope: Product, search, gateway, rating, promotion, shipping, tax, and favourite.
> Observed HEAD: a48f158.

## Evidence-backed findings

| Finding | Commit evidence | Test/verification evidence | Honest result |
|---|---|---|---|
| H16 category cycle guard | 7264275 | CategoryServiceImplTest changed | Implemented; standalone rerun not recorded. |
| H17 key-scoped cache eviction | c0ffbd7 | ProductServiceImplTest and media-clear test changed | Implemented; standalone rerun not recorded. |
| H20 storefront page cap | 9bcef3c | ProductControllerTest changed | Implemented; standalone rerun not recorded. |
| H21 storefront ACTIVE default | No distinct commit identified | No result identified | UNRESOLVED / not evidenced. |
| H25 auth guard consolidation | 8178e4f | Source-only change | Implemented in source; verification not evidenced. |
| H18 deep-pagination protection | 82701cf | SearchQueryServiceTest changed | Implemented; standalone rerun not recorded. |
| H24 reindex cleanup errors surfaced | 3b7d63a | No result identified | Implemented in source; verification not evidenced. |
| H37 search_after/PIT | No commit identified; deep pagination is rejected instead | No result identified | NOT IMPLEMENTED AS PLANNED; unresolved. |
| H4 bounded rate-limit state | 727865f, 26cb5bb | No result identified | Implemented in source; verification not evidenced. |
| H28 trusted forwarded-for handling | 347d510, 26cb5bb | ClientIpResolverTest changed | Implemented; standalone rerun not recorded. |
| H39 promotion query index | c284bff | Changelog changed | Implemented in changelog; migration verification not evidenced. |
| H49 idempotent promotion reservation | 78b154d | CampaignReserveTest added | Implemented; standalone rerun not recorded. |
| H48 shipment status constraint | No reliable commit identified | No reliable result identified | UNRESOLVED. |
| H50 tax path/validation/duplicates/round trips | f5708a5, 509d4cb, fa216fa, adabff8 | Tax tests changed | Implemented in source; standalone result not recorded. |
| Favourite sanity | 3a0a2e2, 472c23d, 44e3c50 | No Wave C battery identified | Prior changes exist; no new completion claim. |

## Tests actually run

- ./mvnw -T1C -pl utils/common-patterns test: 6 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- ./mvnw -T1C test was run twice and exited 1. Output included Testcontainers Kafka/Redis shutdown and reconnect messages. No total test count or fleet pass is claimed.

## Skipped and unresolved

H21, H37, and H48 remain unresolved or not evidenced. Rating H7 has no Wave C commit/test evidence. No per-service battery, boot proof, clean-package proof, or all-modules-green result is claimed. The planned Wave C deliverable is not demonstrated.

## Verified commit inventory

7264275, c0ffbd7, 9bcef3c, 8178e4f, 82701cf, 3b7d63a, 727865f, 347d510, 26cb5bb, c284bff, 78b154d, adabff8, f5708a5, 509d4cb, fa216fa.

No production changes are made by this report; it is an evidence ledger, not a completion claim.
