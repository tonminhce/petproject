# Test Suite & Verification

Generated: 2026-11-10T10:05:42

Findings in this category: **2**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-042 | P1 | VERIFIED | The media-service integration suite referenced the non-existent Docker image 'minio/minio:latest' - 44 integra |
| F-043 | P2 | VERIFIED | Shared Testcontainers databases are polluted across test classes, making several integration tests order-depen |

---

### F-042 - The media-service integration suite referenced the non-existent Docker image 'minio/minio:latest' - 44 integration tests could never run, anywhere

- **Priority / severity:** P1 / High (confidence: RUNTIME_MEASURED, evidence: RUNTIME_MEASURED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** media-service
- **Source files:**
  - `media-service/src/test/java/com/shop/mediaservice/support/AbstractMediaIntegrationTest.java`
- **Actual implementation:** AbstractMediaIntegrationTest declared new MinIOContainer(DockerImageName.parse("minio/minio:latest")). That tag no longer exists on Docker Hub: 'docker pull minio/minio:latest' and 'docker pull minio/minio:RELEASE.2024-09-13T20-26-02Z' both return 'pull access denied for minio/minio, repository does not exist or may require docker login'. The container start therefore failed in the static initializer with ContainerFetchException -> ExceptionInInitializerError -> NoClassDefFoundError for every media integration test class (UploadIT 13, MediaOutboxIT 10, MediaOutboxClaimConcurrencyIT 2, MediaOutboxRetentionIT 3, MediaPurgeIT 4, ProvisioningIT 4 and others - 44 errors in total). The suite also used a DIFFERENT S3 implementation from production, which deploys RustFS.
- **Expected:** Integration tests must be runnable in CI and locally, and the object-storage test double should be the implementation the platform actually ships against.
- **Trigger:** Any execution of the media-service integration suite.
- **Root cause:** An unpinned and now-deleted upstream image tag, plus a fidelity gap between the test double (MinIO) and production (RustFS).
- **Consequences:** 44 integration tests were permanently unrunnable and therefore silently unverified: upload, WebP processing, presigned URLs, outbox relay/retention/claim concurrency, purge job and bucket provisioning for the media domain. A green 'mvn test' in CI would have been impossible for media-service, and the media domain's correctness claims had no executable evidence.
- **Required remediation:** Replace the MinIO container with the production RustFS image (rustfs/rustfs:1.0.0-rc.5) and the same credential environment the compose stack uses (RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY), with a readiness wait on the S3 health endpoint. This both unblocks the suite and raises fidelity to the shipped backend.
- **Regression test:** The media-service module suite itself is the regression evidence: 126 tests, 0 failures after the change (44 errors before).
- **Remediation applied:** AbstractMediaIntegrationTest now boots rustfs/rustfs:1.0.0-rc.5 with RUSTFS_VOLUMES/RUSTFS_ADDRESS/RUSTFS_CONSOLE_ADDRESS/RUSTFS_CONSOLE_ENABLE/RUSTFS_ACCESS_KEY/RUSTFS_SECRET_KEY, waits on /minio/health/live, and publishes the endpoint through shop.storage.endpoint.
- **Verification evidence:** media-service 126/126 green (previously 44 errors) against the production RustFS image.
### F-043 - Shared Testcontainers databases are polluted across test classes, making several integration tests order-dependent

- **Priority / severity:** P2 / Medium (confidence: RUNTIME_MEASURED, evidence: RUNTIME_MEASURED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** inventory-service, promotion-service, notification-service, auth-service
- **Source files:**
  - `inventory-service/src/test/java/com/shop/inventoryservice/support/AbstractIntegrationTest.java`
  - `promotion-service/src/test/java/com/shop/promotionservice/support/PromotionBootstrapIT.java`
  - `notification-service/src/test/java/com/shop/notificationservice/NotificationFlowIT.java`
  - `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxClaimConcurrencyTest.java`
- **Actual implementation:** Each service boots ONE PostgreSQL/Kafka container per JVM and every integration test class in the module shares it. Several tests assume an empty table or a stable row ordering. Observed failures: InventoryOutboxClaimConcurrencyTest failed with 'Expecting actual: Optional[2] to contain: 3L' because a background @Scheduled job from another cached ApplicationContext inserted an outbox row between the test's purge and its claim; PromotionBootstrapIT failed with 'expected: 0L but was: 16L' because another test class had created 16 campaigns; NotificationFlowIT asserted a terminal row status immediately after the row appeared (the row is inserted PENDING and updated later), which is a race; auth-service logged 'duplicate key value violates unique constraint users_username_key' from a repeated username.
- **Expected:** Every integration test must be deterministic regardless of the order in which the module's test classes run.
- **Trigger:** Running the whole module's suite (the tests pass individually).
- **Root cause:** A shared container plus several cached ApplicationContexts, each with its own @Scheduled tasks, all mutating the same database while other test classes are asserting on it; plus tests that assert on non-final state without waiting for it.
- **Consequences:** The suite is order-dependent and flaky. Two of the failures were masked entirely by the timezone defect (F-039): the integration contexts could not boot, so the polluting code never ran. Fixing the timezone exposed them - a good illustration that a defect can hide the evidence of another.
- **Required remediation:** inventory: disable the background relay and the reservation-cleanup sweep by default in the IT base and delay the sweep's first run; promotion: truncate the campaign tables before asserting an empty table; notification: wait for the terminal row status instead of the row's existence.
- **Regression test:** The module suites themselves - inventory-service 64 tests, promotion-service 120 tests, notification-service 94 tests, all green and order-independent.
- **Remediation applied:** inventory AbstractIntegrationTest pins inventory.outbox.poll-interval-ms and inventory.reservation-cleanup-*-ms to 1h; ReservationCleanupScheduler gained an initialDelayString; promotion PromotionBootstrapIT truncates before asserting; notification NotificationFlowIT gained awaitRowWithStatus.
- **Verification evidence:** inventory 64/64, promotion 120/120, notification 94/94 green and order-independent.
