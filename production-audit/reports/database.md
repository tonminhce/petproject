# Database & Data Integrity

Generated: 2026-11-10T10:05:42

Findings in this category: **2**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-015 | P3 | OPEN | reservations table has no index on order_id and no foreign key to inventory.product_id |
| F-059 | P2 | VERIFIED | product-service and rating-service had no outbox retention scheduler - their outbox tables grew without bound |

---

### F-015 - reservations table has no index on order_id and no foreign key to inventory.product_id

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** inventory-service
- **Source files:**
  - `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`
- **Actual implementation:** The reservations table declares indexes on product_id and on (status, expires_at) but no index on order_id, and no foreign key from reservations.product_id to inventory.product_id. The order_id column is written on every reserve.
- **Expected:** A reconciliation query that looks reservations up by order id must not require a sequential scan, and the reservation must not be able to reference a product that does not exist.
- **Trigger:** Order-service reconciliation or an operational query filtering reservations by order_id.
- **Root cause:** Indexes were created for the reserve/commit path but not for the by-order lookup path.
- **Consequences:** Sequential scans on the reservations table as it grows towards the 30-day retention volume; orphaned reservations are possible if an inventory row is deleted by a path that bypasses the service check.
- **Required remediation:** Add an index on reservations(order_id) via a new Liquibase changeset. Assess the FK separately - inventory.product_id is unique and the service already blocks deletes with active reservations.
- **Regression test:** Migration test asserting the index exists and is used by an EXPLAIN on a by-order_id query.
- **Remediation applied:** Not yet implemented - the reservations(order_id) index is still missing. Tracked as task TSK-015.
- **Verification evidence:** (none yet)
### F-059 - product-service and rating-service had no outbox retention scheduler - their outbox tables grew without bound

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** product-service, rating-service
- **Source files:**
  - `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java`
  - `product-service/src/main/java/com/shop/productservice/service/OutboxRetentionScheduler.java`
  - `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxRetentionScheduler.java`
- **Actual implementation:** A fleet-wide scan for a retention scheduler showed inventory, promotion, media, payment, shipping and order all purge SENT outbox rows older than seven days; product-service and rating-service did not. product-service's repository did not even have the delete method, and rating-service HAD the method but no scheduler ever called it. Their outbox_events tables therefore grew by one row per domain mutation forever.
- **Expected:** Every event-producing service reclaims the space its outbox consumes.
- **Trigger:** Normal operation.
- **Root cause:** The retention job was added service by service and two were missed.
- **Consequences:** Unbounded table growth in two services - storage cost, ever-growing indexes, and slower claim queries (the claim does a MIN(id) sub-select over the PENDING set).
- **Required remediation:** Added an OutboxRetentionScheduler to product-service (plus the missing repository delete method) and to rating-service (which already had the method). Only SENT rows are purged; PENDING, FAILED and DEAD rows are left for the relay and for operators.
- **Regression test:** Integration test: after the retention job runs, SENT rows older than the window are gone while PENDING and FAILED rows remain.
- **Remediation applied:** New OutboxRetentionScheduler in product-service and rating-service; deleteByStatusAndSentAtBefore added to product-service's repository.
- **Verification evidence:** product-service and rating-service green with the new OutboxRetentionScheduler beans.
