# Tax Service — Design

Date: 2026-08-30 · Status: Approved (pending user spec review) · Pipeline: spec → plan → SDD (concurrent with notification-service epic, separate worktree, tax merges first)

## 1. Purpose

Backoffice-managed tax rate catalog + the calculation endpoint the order confirm
saga already calls (`TaxServiceClient`). Today order runs with
`props.tax().isEnabled() == false` and hardcodes zero tax; this service fills
that hole and lets the saga enable real tax. Pure synchronous service: no
Kafka, no outbox, no cache, no events.

## 2. Binding decisions

- **D1 — Calculate contract is byte-compatible with the shipped client.**
  `POST /api/v1/backoffice/tax-rates/calculate`, request
  `{ "taxClassId": UUID, "country": "VN", "postalCode": "700000", "amount": 100.00 }`
  → `ApiResponse<{ "taxAmount": 10.00, "appliedRate": 10.00 }>`.
  `country` = ISO-3166 alpha-2 uppercase; `postalCode` may be blank/null
  (means country-level lookup); `amount` ≥ 0. Unknown/blank fields → 400 via
  bean validation. Order treats 4xx as `ORDER_TAX_CALCULATION_FAILED` and 5xx
  as fail-closed `SERVICE_UNAVAILABLE` — so this service must never 5xx on bad
  business input (validation errors are 400, unknown ids are TAX-8xxx 4xx).
- **D2 — Fallback chain (user decision Q1=A).** Resolve rate for
  `(taxClassId, country, postalCode)`:
  1. exact row: class + country + postal match
  2. country row: class + country + `postal_code IS NULL`
  3. class default: `tax_classes.default_rate_pct`
  No match (incl. no class row) → TAX-8xxx 404-class error, never 5xx.
- **D3 — Model mirrors the Campaign precedent.** Entities `TaxClass`
  (id UUID, name unique among live rows, `default_rate_pct`, explicit
  `@Version`, soft-delete via `SoftDeletable`, `@SQLRestriction("deleted = false")`)
  and `TaxRate` (id UUID, FK `tax_class_id`, `country`, nullable
  `postal_code`, `rate_pct`, explicit `@Version`, soft-delete same set).
  Both extend `AbstractMappedEntity` with the full 10-column audit set.
- **D4 — Rounding.** `rate_pct` is a percentage `numeric(5,2)`;
  `taxAmount = amount × rate_pct / 100`, `numeric(19,2)`, scale 2,
  `RoundingMode.HALF_UP` (DiscountCalculator precedent). `appliedRate`
  echoes the resolved row's pct (or the class default).
- **D5 — Uniqueness.** Live rates unique per
  `(tax_class_id, country, COALESCE(postal_code, ''))` via a Postgres
  expression unique index `WHERE deleted = false` (NULL postal dedupe needs
  the COALESCE expression; declarative `createUniqueConstraint` is not
  enough). Violation → TAX-8003, never a raw 500. Class name unique among
  live rows (partial unique, same style as `uk_campaign_code_live`).
- **D6 — Delete guards.** Soft-deleting a TaxClass with ≥1 live rate →
  TAX-8004 (delete the rates first). Soft-deleting a rate is always allowed.
  No reservation-style in-flight risk: calculate reads committed rows only.
- **D7 — Error codes.** `TAX-8001` `TAX_CLASS_NOT_FOUND`,
  `TAX-8002` `NO_MATCHING_RATE`, `TAX-8003` `DUPLICATE_TAX_RATE`,
  `TAX-8004` `TAX_CLASS_IN_USE` — appended to the shared `ErrorCode` enum
  after the current last entry, `;`-terminated, + `tax.*` i18n keys EN+VI.
- **D8 — Security.** Fail-closed: no public paths. Calculate:
  `SERVICE or ADMIN` (order authenticates with a service token —
  `TaxServiceClient` client note). Backoffice CRUD: `ADMIN`.
  JWT issuer as the rest of the fleet (compose `x-jwt` anchor).
- **D9 — No async surface.** No outbox, no Kafka, no Redis, no scheduler.
  The service is a stateless catalog + calculator; scale/complexity budget
  stays minimal.
- **D10 — Deployment.** Port 8091, DB `taxservice` (compose stanza + init SQL
  already exist). Order keeps its flag gate; enabling real tax is the
  post-deploy step documented in §8 (order-side `tax.enabled` flag env, exact
  binding per `ShopServicesProperties` verified at plan time).

## 3. API

| Method | Path | Auth | Request | Response / errors |
|---|---|---|---|---|
| POST | `/api/v1/backoffice/tax-rates/calculate` | SERVICE, ADMIN | `TaxCalculateRequest` | 200 `ApiResponse<TaxCalculateResponse>`; 400 validation; 404 `TAX-8001/8002` |
| GET/POST | `/api/v1/backoffice/tax-classes`, `/{id}` GET/PUT/DELETE | ADMIN | `TaxClassRequest` | standard CRUD; 404; 409 `TAX-8003/8004` family; delete = soft |
| GET/POST | `/api/v1/backoffice/tax-rates` (+ `/{id}` PUT/DELETE, `?classId=` filter) | ADMIN | `TaxRateRequest` | standard CRUD; 409 `TAX-8003` |

`TaxRateRequest` validation: country `^[A-Z]{2}$`, `rate_pct` 0–100,
`postal_code` optional, class must exist and be live.

## 4. Calculate flow

1. Validate request (bean validation, 400 on violation).
2. Load class (live only) → TAX-8001 if absent.
3. Rate lookup with the D2 chain — 3 indexed queries max; single service
   transaction, read-only.
4. Compute `taxAmount` (D4). Return 200 with `{taxAmount, appliedRate}`.

Performance envelope: all lookups are index hits
(`idx_tax_rates_class_country_postal`, `idx_tax_rates_class_country`);
no cache by design (D9).

## 5. Testing strategy (fleet 3 layers)

- **Unit/service**: calculate chain (all 3 tiers + no-match), rounding edges
  (0, 100 pct, HALF_UP), CRUD validation, delete guards, duplicate rate.
- **Controller slices**: real security chain — calculate anonymous 401 /
  user 403 / SERVICE 200 / ADMIN 200; backoffice ADMIN-only matrix.
- **IT (Testcontainers PG)**: full context + Liquibase; unique expression
  index actually rejects dupes (TAX-8003 surface), fallback chain against
  seeded rows, soft-delete visibility, no-match → TAX-8002.

## 6. Fleet impact

`utils/common-core`: +4 `ErrorCode` entries, `ApiPaths` additions
(`BACKOFFICE_TAX_CLASSES`, `BACKOFFICE_TAX_RATES`), i18n keys — additive only,
merged after the tax epic wins the shared-file race (tax merges first by plan).

## 7. Out of scope

Region hierarchy (Q1=C), historical rates / effective-dating, invoice
generation, product-category-derived tax classes (mapping stays backoffice
manual), caching.
