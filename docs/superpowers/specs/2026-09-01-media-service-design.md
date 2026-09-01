# Media Service — Design

- Date: 2026-09-01
- Status: Approved (production-grade scope per user ruling — no MVP cuts; presigned
  private storage, variants+WebP, EXIF strip, dedup, outbox events, product integrity)
- Scope: media-service skeleton → FULL; product-service: media_id reference +
  MediaDeleted consumer. Gateway zero (ServiceRoute MEDIA 8083 pre-wired).

## Verified ground truths

- `ServiceRoute.MEDIA` = resource `media`, port 8083; `ApiPaths.MEDIAS = /api/v1/medias`
  exists (COMMON-LIB table). Compose has `rustfs` service (S3 API) + media stanza
  likely skeleton-era — verify env keys at plan time (search's KAFKA_SERVERS lesson).
- utils/**common-storage** module exists — wraps object storage client (inspect at
  plan time; wire S3-compatible RustFS per compose envs).
- product.imageUrl is a free string today (payloads: product lifecycle D2 snapshot,
  search doc). Products table has no media reference.
- Fleet precedents binding here: outbox snapshot-carry (rating), consumer dumb-copy
  (product rating consumer), P2-6, ErrorCode chaining (tail after SRH-12002),
  test-cache rules 1-6 if caching lands (media adds NONE — presign is not cacheable
  state; dedup lookup is a DB unique index, not Redis).

## D1 — Storage (private bucket, presigned reads)

RustFS/MinIO via S3 API through common-storage. Bucket `media` — **PRIVATE**
(created-on-startup if missing, idempotent, private ACL asserted). All reads go
through presigned GET (302 from media-service), expiry `MEDIA_PRESIGN_TTL`
(default 7d, config). Upload: multipart streaming, `MEDIA_MAX_UPLOAD` (default 10MB,
413 over), mime allowlist **image/jpeg|png|webp** + magic-byte validation (415),
SHA-256 content hash with UNIQUE index — duplicate upload returns the EXISTING media
(200 + `duplicate:true`), never a second object. Object keys: `{mediaId}/{variant}.{ext}`.

## D2 — Variants & processing (on-upload, synchronous)

thumbnailator (new dep, media only). Per upload: **original** (byte-preserved
AFTER EXIF/GPS metadata strip — PII), **display** (max 1200w), **thumb** (max 320w);
each stored in original format AND WebP. One media row + 6 variant rows
(variant, format, width, bytes, objectKey). Processing is synchronous (≤5MB images);
failures → 400/503 per D5, no partial media (variants written first, media row
commits last — S3 has no tx: orphan objects on failure purged by best-effort
cleanup + §4 purge note).

## D3 — Surface

- `POST /api/v1/backoffice/medias` — ADMIN, multipart → 201 `MediaResponse{id, sha256,
  contentType, sizeBytes, variants[], canonicalPath}` (or 200+existing on dedup).
- `GET /api/v1/medias/{id}?variant=display|thumb|original&format=auto|webp` — public
  (P2-6, authenticated edge; default display+auto=webp when stored) → **302 presigned**.
  Unknown variant → 404.
- `DELETE /api/v1/backoffice/medias/{id}` — ADMIN → soft-delete (deleted_at) +
  MediaDeleted event (D4); repeat → 409. Hard purge of objects after
  `MEDIA_PURGE_GRACE` (default 30d) by scheduled job (@EnableScheduling, port rating's
  relay scheduling pattern; purge skips media still referenced — log WARN, retry next cycle).

## D4 — Events & product integrity (production orphan-kill)

Outbox `media.lifecycle.v1` (aggregateId=mediaId → per-media partition order),
eventTypes `MediaCreated`/`MediaDeleted`, FULL snapshot payload (mediaId, sha256,
contentType, canonicalPath `/api/v1/medias/{id}`, variants, occurredAt — snapshot-carry
precedent). `MediaCreated` published for audit/future CDN hooks.
**product-service consumer** (`MediaLifecycleListenerConfig`, group product-service):
on `MediaDeleted` → products with `media_id = id` → clear media_id (+ derived image
fields) → **emit ProductUpdated** (existing enriched publisher) → flows to search
doc automatically (chain: media delete → product update → search refresh). Unknown
eventTypes ack-skip; containment fleet posture; NO DLT.

## D5 — Product schema (W2 enabler)

changelog-004: `products.media_id` UUID NULL (logical ref — media DB is SEPARATE, no
FK), backfill none (legacy rows keep free-text imageUrl). Mapper: mediaId present →
canonicalPath; else legacy imageUrl (backward compat). ProductDetail/Summary +
create/update requests gain `mediaId`; lifecycle payload's `imageUrl` field becomes
the DERIVED canonical path when mediaId set (search doc unchanged — contract stable).
Rating-referenced product payload parity test updated (same 17 names, new value source).

## D6 — Errors, i18n, metrics

ErrorCode chain tail AFTER `SRH-12002` (re-anchored post-search):
`MEDIA_INVALID_FILE("MED-12001",400)` `,` `MEDIA_TOO_LARGE("MED-12002",413)` `,`
`MEDIA_TYPE_NOT_ALLOWED("MED-12003",415)` `,` `MEDIA_NOT_FOUND("MED-12004",404)` `,`
`MEDIA_ALREADY_DELETED("MED-12005",409)` `,` `MEDIA_STORAGE_UNAVAILABLE
("MED-12006",503)` `;`. i18n EN+VI ×6. Meters: `media_uploads_total{outcome}`
(created|duplicate|rejected), `media_presigned_total{variant}`.

## §4 Ops & contracts

1. Startup bucket bootstrap idempotent; presign TTL and grace configurable.
2. Event contract: additive snapshot; per-media ordering; consumers ack-skip unknown.
3. Prod deploy: set MEDIA_MAX_UPLOAD/PRESIGN_TTL/PURGE_GRACE + RustFS creds via env;
   audit of upload/delete endpoints comes free via production-readiness @Audited (D6 there).
4. Orphan objects (failed uploads) — best-effort delete + log; §6 purge extension.

## §5 Non-goals (binding)

Virus scanning (ClamAV — open item), CDN integration (presigned layer is the seam),
video/non-image types, zip/batch upload, image moderation, media library UI
(frontend epic), per-variant ACLs.

## §6 Open items

ClamAV scan stage; CDN fronting; EXIF-based auto-tagging; orphan purge sweeper
beyond best-effort; per-variant access control.
