# Media Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** media-service skeleton → FULL (private S3 storage, presigned reads, variants,
dedup, outbox events) + product-service media_id integrity consumer.

**Spec (binding):** docs/superpowers/specs/2026-09-01-media-service-design.md

**Tech:** common-storage (inspect first — S3 client shape), thumbnailator (new),
Testcontainers MinIO + postgres + kafka, fleet outbox/consumer precedents.

## Global constraints

- Port 8083; gateway `ServiceRoute.MEDIA` pre-wired — zero gateway changes.
- ErrorCode chain AFTER SRH-12002 (flip `,`; MED block per spec D6, ends `;`).
- P2-6 on GET /api/v1/medias; ADMIN on backoffice upload/delete.
- Consumer containment no-DLT; outbox snapshot-carry; per-mediaId key.
- Copy-sources: search-service (latest bootstrap: IT base w/ kafka, client props,
  controller slices, meter), rating (outbox+relay), product (consumer stack).
- Verify media compose stanza env keys against actual yml bindings (search's
  KAFKA_SERVERS lesson); fix in T6 if stale.

---

### Task 1: bootstrap — yml, storage wiring, bucket bootstrap, IT base

**Files:** application.yml (port 8083; `media.{bucket,presign-ttl,max-upload,purge-grace}`,
rustfs/S3 endpoint+creds env-backed, shop.kafka block group media-service latest),
StorageConfig (common-storage client bean from props), BucketBootstrap
(ApplicationRunner: create-if-missing + ASSERT private ACL, ES-down-style tolerance),
MediaProperties record; test support AbstractMediaIntegrationTest (Testcontainers:
postgres + kafka + **MinIO** — port search's singleton pattern; MinIO with bucket env).
Test: ProvisioningIT — context boots, bucket exists + private, props bound.
- [ ] TDD → green → **commit** `feat(media): bootstrap — storage client, private bucket bootstrap, IT base`

### Task 2: upload — validation, dedup, variants, EXIF strip

**Files:** Media entity + variants child (schema per D1/D2, changelog-001),
MediaRepository (sha256 unique), upload pipeline: magic-byte + mime allowlist →
size guard → SHA-256 (dedup → return existing 200 duplicate:true BEFORE storing) →
EXIF/GPS strip → thumbnailator variants (original/display/thumb × original-format+WebP)
→ S3 writes → media row commit LAST; failure → best-effort orphan delete + 503/400.
MediaUploadService interface + impls (fleet split), MediaProperties binding.
ErrorCode MED-12001..12003 + i18n + `media_uploads_total{outcome}`.
Tests: UploadIT (MinIO): each format ok (variants exist, sizes ≤ caps, WebP present),
oversize 413, wrong-mime 415, corrupt magic 400, dedup second upload 200+same id,
orphan cleanup on injected S3 failure.
- [ ] TDD → green → **commit** `feat(media): upload pipeline — validation, dedup, variants, EXIF strip`

### Task 3: read + delete — presign 302, soft delete, purge

**Files:** MediaQueryService (presign per variant/format, unknown → 404 MED-12004),
MediaLifecycleService (soft-delete + outbox row, repeat 409 MED-12005),
MediaPurgeJob (@EnableScheduling, grace-aware, reference-aware WARN-skip),
BackofficeMediaController (ADMIN upload/delete), MediaPublicController (GET P2-6 → 302),
errors MED-12004..12006 + i18n + `media_presigned_total{variant}`.
Tests: controller slices (401/403/201/200-dup/409), presign matrix (variants, formats,
404), purge job unit (grace boundary, referenced-skip), storage-down → 503 MED-12006.
- [ ] TDD → green → **commit** `feat(media): presigned reads, soft delete, purge job, public+backoffice controllers`

### Task 4: outbox events + relay

**Files:** OutboxEvent entity/repo (port rating), MediaEventService (snapshot payload
per spec D4 — MediaCreated on upload commit, MediaDeleted on soft-delete; scale/field
parity test pinning EXACT names), MediaOutboxRelay (port rating/search relay —
aggregateId=mediaId key), metrics reuse.
Tests: payload parity pin (17-style field pin), relay → real kafka topic assertion,
same-tx outbox row IT.
- [ ] TDD → green → **commit** `feat(media): lifecycle outbox — snapshot payloads + relay (key=mediaId)`

### Task 5: product integrity — media_id + MediaDeleted consumer

**Files:** product-service: changelog-004 (products.media_id UUID NULL), Product
entity + mapper (mediaId → canonicalPath derive; legacy imageUrl fallback per spec D5),
create/update request fields, **MediaLifecycleListenerConfig + MediaDeletedConsumer**
(product's consumer stack; group product-service): on MediaDeleted → clear matching
media_id products → publishUpdated(each) → (search chain verified by existing consumer
tests' pattern); ProductBackofficeClient upload-attach: backoffice product
create/update accept mediaId (no sync media check — integrity is event-driven).
Tests: changelog roll, mapper derive/fallback, consumer clears + emits ProductUpdated
(real kafka), payload parity test updated (17 names, derived imageUrl), product suite
green (62+).
- [ ] TDD → green → **commit** `feat(product): media_id reference + MediaDeleted consumer (orphan kill → ProductUpdated)`

### Task 6: compose + cross-module battery

**Files:** docker-compose.yml media stanza (verify/fix env keys vs yml: RUSTFS/S3
endpoint+creds, KAFKA key SHOP_KAFKA_BOOTSTRAP_SERVERS, KEYCLOAK_TOKEN_URL,
media-service client), .env.example media section.
Verify: compose config -q; install -q; media + product + order + search suites green;
gateway 19/19.
- [ ] **commit** `chore(compose): media stanza — env bindings + client config`

### Task 7: final whole-branch review

- [ ] Reviewer: whole diff; spec D1-D6 + §4/§5 audit; E2E hops (upload → variants →
  dedup → presign 302; delete → outbox → product consumer → ProductUpdated → search
  refresh; purge grace/skip); PII (EXIF stripped — assert metadata gone in stored
  variant); private-bucket proof (unpresigned GET denied); no caching introduced;
  fleet pattern conformance (interface+impls, consumer stack, ErrorCode/i18n, meters).
  Fix rounds until APPROVED.
