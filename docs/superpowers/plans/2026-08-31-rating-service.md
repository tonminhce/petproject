# Rating Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rating-service skeleton → FULL (verified-purchase ratings + outbox events), product-service denormalized stars, one order-service verify endpoint.

**Architecture:** Sync eligibility (rating → order-service at submit), outbox → Kafka `shop.rating.lifecycle.v1` keyed by productId with snapshot aggregates, product's first consumer dumb-copies `avg_rating`/`rating_count`. Spec is binding authority: `docs/superpowers/specs/2026-08-31-rating-service-design.md`.

**Tech Stack:** Spring Boot 4 (fleet BOM), Liquibase yaml changelogs, common-core/common-spring/common-security/common-kafka, PostgreSQL, spring-kafka.

**Spec:** docs/superpowers/specs/2026-08-31-rating-service-design.md

## Global Constraints

- Port 8089; gateway `ServiceRoute.RATING` already exists — **zero gateway changes**.
- ErrorCode block RTG-11xxx appended after `ORDER_PAYMENT_NOT_CAPTURED("ORD-4012", …)` — the last enum entry flips `,`→`;`.
- Storefront endpoints: NO `@PreAuthorize` on user methods (P2-6, `OrderController.java:32` rationale); class-level auth + owner checks in service layer. Backoffice = `@PreAuthorize("hasRole('ADMIN')")`. verify-purchase = `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")`.
- Event envelope per spec D4 verbatim: `eventType:"rating.submitted.v1"`, `action: CREATED|UPDATED|HIDDEN|UNHIDDEN`, `visible`, snapshot `avgRating`+`ratingCount`. Kafka key = productId string.
- Copy-sources (read them before writing): `PaymentServiceClient`, `order ServiceTokenProvider`, `PaymentOutboxRelay`, `ShippingListenerConfig`, payment `changelog-001-payments.yaml`.
- Test commands per module: `./mvnw -pl rating-service test` etc. from repo root. Final gate: `./mvnw -pl order-service,rating-service,product-service test` all green.
- i18n: `utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties`.
- JDK 26: no `HexFormat.fromHexDigitsToByteArray` (removed).

---

### Task 1: rating-service bootstrap — pom, config, changelog, entities

**Files:**
- Modify: `rating-service/pom.xml` (copy payment deps: common-core, common-spring, common-security, spring-boot-starter-data-jpa, spring-boot-starter-liquibase, spring-kafka, common-kafka, liquibase-core, postgresql, lombok, spring-boot-starter-validation, actuator via common-spring; test: starter-test, webmvc-test, security-test, data-jpa-test. DROP common-storage + resilience4j + common-keycloak unless payment needed them for tests — check payment pom)
- Create: `rating-service/src/main/resources/application.yml` (port 8089, datasource `jdbc:postgresql://localhost:5432/ratingservice`, liquibase, `shop.kafka` block copied from shipping yml, `shop.rating.outbox.poll-millis:2000/batch-size:100/max-retries:10`, `shop.rating.order-service.url:${ORDER_SERVICE_URL:http://localhost:8084}` timeout-ms 3000, `shop.rating.keycloak.{token-url,client-id:${RATING_SERVICE_CLIENT_ID:rating-service},client-secret:${RATING_SERVICE_CLIENT_SECRET:changeme}}`)
- Create: `rating-service/src/main/resources/db/changelog/db.changelog-master.yaml` + `changelog-001-ratings.yaml`
- Create: `rating-service/src/main/java/com/shop/ratingservice/constant/RatingAction.java`
- Create: `rating-service/src/main/java/com/shop/ratingservice/entity/Rating.java`
- Create: `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxEvent.java` (port payment's verbatim, package changed)
- Create: `rating-service/src/main/java/com/shop/ratingservice/repository/RatingRepository.java`, `outbox/OutboxEventRepository.java` (port payment's)

**Interfaces:**
- Produces: `Rating` entity fields `id, productId, userId, rating(int), comment, verified, hidden, hiddenAt(Instant), hiddenBy(UUID), hiddenReason, editedAt(Instant)` + `AbstractMappedEntity` audit; `RatingAction` enum `CREATED, UPDATED, HIDDEN, UNHIDDEN`; `RatingRepository.findByProductIdAndHiddenFalseAndDeletedFalse(UUID, Pageable)`, `findByUserIdAndProductIdAndDeletedFalse(UUID, UUID)` returns `Optional<Rating>`; `OutboxEventRepository.findByStatusOrderByIdAsc(OutboxStatus, Pageable)`.

- [ ] **Step 1: pom + yml + changelog.** Changelog `001-create-ratings` (liquibase yaml, payment style) — columns exactly per spec D3 (uuid pk, product_id, user_id, rating smallint, comment text, verified/hidden/deleted booleans default false, hidden_at timestamptz, hidden_by uuid, hidden_reason varchar(500), edited_at timestamptz, audit cols created_at/updated_at/created_by/updated_by timestamptz/varchar). Then raw-sql changeSets:

```yaml
      - changeSet:
          id: 001-ratings-constraints
          author: shop-platform
          changes:
            - sql: |
                ALTER TABLE ratings ADD CONSTRAINT ck_ratings_rating_range CHECK (rating >= 1 AND rating <= 5);
                ALTER TABLE ratings ADD CONSTRAINT ck_ratings_comment_length CHECK (char_length(comment) >= 5 AND char_length(comment) <= 2000);
                ALTER TABLE ratings ADD CONSTRAINT ck_ratings_audit CHECK ((hidden = false) OR (hidden = true AND hidden_at IS NOT NULL AND hidden_by IS NOT NULL));
                CREATE UNIQUE INDEX uk_rating_user_product_live ON ratings (user_id, product_id) WHERE deleted = false;
                CREATE INDEX idx_ratings_product_live ON ratings (product_id) WHERE deleted = false AND hidden = false;
      - changeSet:
          id: 002-create-outbox-events
          author: shop-platform
          changes:
            - sql: |
                CREATE TABLE outbox_events (
                  id BIGSERIAL PRIMARY KEY, event_id varchar(36) NOT NULL UNIQUE,
                  aggregate_type varchar(50) NOT NULL, aggregate_id uuid NOT NULL,
                  event_type varchar(50) NOT NULL, topic varchar(100) NOT NULL,
                  payload TEXT NOT NULL, status varchar(20) NOT NULL,
                  retry_count integer NOT NULL DEFAULT 0, sent_at timestamptz, last_error varchar(1000));
                CREATE INDEX idx_outbox_status ON outbox_events (status);
```

- [ ] **Step 2: entities + enums.** `Rating` mirrors payment entity style (`@GeneratedValue(strategy = GenerationType.UUID)`); `OutboxEvent` = payment's verbatim.
- [ ] **Step 3: failing test** `RatingMappingIT` (`@SpringBootTest`, Testcontainers postgres — copy payment's IT base/config): persist a valid Rating row; assert `char_length(comment)=4` → constraint violation; `rating=6` → violation; duplicate (user,product) second insert → unique violation; hidden=true without hidden_at → audit-check violation.
- [ ] **Step 4: run** `./mvnw -pl rating-service test` → PASS.
- [ ] **Step 5: commit** `feat(rating): module bootstrap — schema, entities, config`

### Task 2: EligibilityClient + security plumbing

**Files:**
- Create: `rating-service/src/main/java/com/shop/ratingservice/security/ServiceTokenProvider.java` (copy order's verbatim; take `tokenUrl/clientId/clientSecret` from new props)
- Create: `rating-service/src/main/java/com/shop/ratingservice/config/RatingClientProperties.java` (`@ConfigurationProperties("shop.rating")` record `OrderService(String url, long timeoutMs)`, `Keycloak(String tokenUrl, String clientId, String clientSecret)` + `@RecordBuilder`-free — match `ShopServicesProperties` record style + `@ConfigurationPropertiesScan` on app class)
- Create: `rating-service/src/main/java/com/shop/ratingservice/config/RestClientConfig.java` — `@Bean("orderRestClient")` with timeouts (P0-4: NO @Qualifier on @Bean params, order RestClientConfig rationale)
- Create: `rating-service/src/main/java/com/shop/ratingservice/eligibility/EligibilityClient.java`
- Test: `rating-service/src/test/java/com/shop/ratingservice/eligibility/EligibilityClientTest.java`

**Interfaces:**
- Produces: `boolean isEligible(UUID userId, UUID productId)` — fail-closed.

- [ ] **Step 1: failing tests** (MockRestServiceServer bound to the RestClient.Builder; Mockito-mock `ServiceTokenProvider`):

```java
@Test void deliveredItemYieldsTrue() { server.expect(...200 body pageWithOneItem...); assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isTrue(); }
@Test void emptyPageYieldsFalse() { ...200 empty content...; assertThat(...).isFalse(); }
@Test void connectionRefusedFailsClosed() { builder error / 500 → assertThat(...).isFalse(); }
@Test void malformedBodyFailsClosed() { ...200 garbage...; assertThat(...).isFalse(); }
@Test void sendsBearerServiceToken() { verify header "Authorization: Bearer test-token" }
```

Request: `GET {url}/api/v1/orders/verify-purchase?userId=&productId=` — `ParameterizedTypeReference<ApiResponse<PageResponse<OrderItemSnapshot>>>` with local record `OrderItemSnapshot(UUID productId, String productTitle, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal)`. Catch `RestClientException` → false + ERROR log. Null/`data()==null`/`content()==null` → false.
- [ ] **Step 2: run** → RED. **Step 3: implement** (mirror PaymentServiceClient javadoc posture). **Step 4: run** → GREEN. **Step 5: commit** `feat(rating): eligibility client — sync verify-purchase, fail-closed`

### Task 3: submit path + errors + i18n + outbox write

**Files:**
- Modify: `utils/common-core/.../exception/ErrorCode.java` — **verified tail (2026-08-31): line 121 is `ORDER_PAYMENT_NOT_CAPTURED("ORD-4012", "order.payment.not_captured", HttpStatus.CONFLICT);` — flip its `;`→`,`, append RTG-11001..11005 per spec D7 with `RATING_ALREADY_EXISTS("RTG-11005", …)` as the `;` terminator** (nit #3 pinned)
- Modify: `utils/common-spring/src/main/resources/messages/messages_en.properties` + `messages_vi.properties` (5 keys `rating.not_eligible` … `rating.already_exists`)
- Create: `dto/request/RatingSubmitRequest.java` (`@NotNull UUID productId`, `@Min(1)@Max(5) int rating`, `@Size(min=5,max=2000) @NotNull String comment`), `dto/response/RatingResponse.java` (`id, productId, userId, rating, comment, verified, hidden, editedAt, createdAt`)
- Create: `service/RatingService.java` + `service/impls/RatingServiceImpl.java`
- Create: `service/RatingEventService.java` (payload builder + snapshot query + outbox write)
- Test: `RatingServiceImplTest.java` (Mockito), `RatingSubmitValidationTest.java` (@WebMvcTest later task's controller — defer to T5; here service-level validation tests)

**Interfaces:**
- Consumes: `EligibilityClient.isEligible`, `RatingRepository`, `OutboxEventRepository`.
- Produces: `RatingResponse submit(UUID jwtUserId, RatingSubmitRequest)`; internal `RatingEventService.record(rating, RatingAction)` — computes snapshot (`AVG(rating), COUNT(*)` via `@Query("select coalesce(avg(r.rating),0), count(r) from Rating r where r.productId=:p and r.hidden=false and r.deleted=false")` in RatingRepository as `Object[] findAggregateByProductId(UUID)`), writes OutboxEvent (`aggregateType:"rating"`, `aggregateId=ratingId`, `eventType:"rating.submitted.v1"`, `topic:"shop.rating.lifecycle.v1"`, payload JSON via `ObjectMapper` — fields exactly spec D4 incl `visible=!hidden`).

- [ ] **Step 1: failing tests**:
  - `submit_ineligible_failsClosed`: client false → `BusinessException` RTG-11001 403; **and** client-throws variant (`EligibilityClient` never throws, but test the seam anyway — stub to false).
  - `submit_eligible_createsVerifiedRating_outboxRow`: client true → saved rating `verified=true`, one outbox row PENDING, payload contains `"action":"CREATED"`, `"visible":true`, `"avgRating":4.5` (assertEquals on parsed JSON), `"ratingCount":1`.
  - `submit_unverified_whenNotEligibleButAllowed`: N/A — ineligible = hard 403 (spec D1/D6). Instead: `submit_duplicate_conflict` → RTG-11005, no second row, no outbox row.
  - `record_updated_recomputesSnapshot`: rating UPDATED with rating 2 after another user's 5 → payload `avgRating=3.5, ratingCount=2, action=UPDATED`.
- [ ] **Step 2: run** → RED. **Step 3: implement.** `submit` = `@Transactional`; duplicate check → eligibility → `saveAndFlush` (**flush REQUIRED before record — the aggregate JPQL in `record()` must see the new row, nit #1**) → `record(rating, CREATED)`; same `saveAndFlush` discipline in the PUT edit path (Task 4) and hide/unhide (Task 5) before `record`. **Step 4: run** → GREEN. **Step 5: commit** `feat(rating): submit path — eligibility, outbox snapshot events, RTG-11xxx errors`

### Task 4: storefront read + owner edit

**Files:**
- Create: `controller/StorefrontRatingController.java` — `@RequestMapping(ApiPaths.RATINGS)` (ADD `public static final String RATINGS = API_V1 + "/ratings";` to common-core ApiPaths next to line 39)
  - `GET ""` params productId,page,size → `ApiResponse<PageResponse<RatingResponse>>` (visible-only, sort createdAt desc)
  - `POST ""` → 201 submit (owner = `AuthenticatedUser.requireCurrent().id()` — import `com.shop.common.security.jwt.AuthenticatedUser`; NO @PreAuthorize, P2-6)
  - `PUT "/{productId}"` body RatingSubmitRequest-less `{rating, comment}` (`RatingEditRequest`) → own row by `findByUserIdAndProductIdAndDeletedFalse`, 404 RTG-11002, updates + `editedAt=Instant.now()` + `record(rating, UPDATED)`, `verified` untouched
- Create: `dto/request/RatingEditRequest.java`; add `Exceptionhandler` coverage via common handlers (RTG codes already mapped by ErrorCode→status)
- Test: `StorefrontRatingControllerTest.java` (`@WebMvcTest` + common security test config used by payment controller tests — copy its setup), `RatingServiceImplEditTest.java`

- [ ] **Step 1: failing tests**: list returns only visible+live rows (3 rows, 1 hidden → page size 2, newest first); PUT edits own rating (verified stays true, editedAt set, UPDATED event); PUT other user's productId with no own row → 404 RTG-11002; PUT rating=0 → 400 ERR-0422-V; unauthenticated POST → 401.
- [ ] **Step 2: RED → implement → GREEN.** **Step 3: commit** `feat(rating): storefront list + owner edit`

### Task 5: backoffice hide/unhide

**Files:**
- Create: `controller/BackofficeRatingController.java` — `@RequestMapping(ApiPaths.BACKOFFICE_RATINGS)`, class `@PreAuthorize("hasRole('ADMIN')")`:
  - `POST "/{id}/hide"` body `{reason}` (`@NotBlank @Size(max=500)`) → sets hidden trio, `record(rating, HIDDEN)`; already hidden → RTG-11003
  - `POST "/{id}/unhide"` → hidden=false (audit retained), `record(rating, UNHIDDEN)`; not hidden → RTG-11004
- Create: `dto/request/RatingHideRequest.java`
- Test: `BackofficeRatingControllerTest.java` (ADMIN happy paths, both 409s, CUSTOMER token → 403, hide reason >500 → 400)

- [ ] **Step 1: failing tests** (hide → response hidden=true; outbox payload `action=HIDDEN, visible=false`, snapshot excludes hidden row → count drops). **Step 2: RED → implement → GREEN.** **Step 3: commit** `feat(rating): backoffice hide/unhide with audit + events`

### Task 6: outbox relay

**Files:**
- Create: `outbox/RatingOutboxRelay.java` (port PaymentOutboxRelay; props `shop.rating.outbox.*`)
- Verify: spring-kafka producer config present in `application.yml` (`spring.kafka.producer.*` — copy payment's block)
- Test: `RatingOutboxRelayTest.java` (port payment's relay test — embedded/`KafkaMessagePublisher` mocked: success → SENT+sentAt; failure → retryCount++, break; max retries → FAILED)

- [ ] **Step 1: port tests failing where names differ.** **Step 2: GREEN.** **Step 3:** `./mvnw -pl rating-service test` full module green (target: ≥25 tests). **Step 4: commit** `feat(rating): outbox relay`

### Task 7: order-service verify-purchase endpoint

**Files:**
- Modify: `order-service/.../repository/OrderItemRepository.java`:

```java
@Query("""
    SELECT i FROM OrderItem i
    WHERE i.productId = :productId
      AND EXISTS (SELECT o FROM Order o WHERE o.id = i.orderId
                  AND o.userId = :userId AND o.status = com.shop.orderservice.constant.OrderStatus.DELIVERED
                  AND o.deleted = false)
    ORDER BY i.id
    """)
Page<OrderItem> findDeliveredByUserAndProduct(@Param("userId") UUID userId,
    @Param("productId") UUID productId, Pageable pageable);
```

- Modify: order's order query service (locate the service used by OrderController list/read — add `Page<OrderItemResponse> findDeliveredItemsByUserAndProduct(UUID, UUID, Pageable)` mapping via existing OrderMapper)
- Modify: `OrderController.java`:

```java
@GetMapping("/verify-purchase")
@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
public ApiResponse<PageResponse<OrderItemResponse>> verifyPurchase(
        @RequestParam UUID userId, @RequestParam UUID productId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    var pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
    return ApiResponse.ok(orderQueryService.findDeliveredItemsByUserAndProduct(userId, productId, pageable));
}
```

(Use the exact service/field names the controller already wires — read OrderController first.)
- Test: `VerifyPurchaseEndpointTest.java` (SERVICE 200 found/empty; CUSTOMER 403; only DELIVERED counts — SHIPPED/PENDING items absent; another user's item absent)

- [ ] **Step 1: failing tests** → **Step 2: implement** → **Step 3:** `./mvnw -pl order-service test` green (142 + new). **Step 4: commit** `feat(order): verify-purchase SERVICE endpoint for rating eligibility`

### Task 8: product-service — denormalized stars + first consumer

**Files:**
- Create: `product-service/src/main/resources/db/changelog/changelog-003-rating-columns.yaml` (+ register in `db.changelog-master.yaml`):

```yaml
      - changeSet:
          id: 003-add-rating-columns
          author: shop-platform
          changes:
            - sql: |
                ALTER TABLE products ADD COLUMN avg_rating NUMERIC(3,2) DEFAULT 0.00;
                ALTER TABLE products ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;
```

- Modify: `entity/Product.java` (`@Column(name="avg_rating", precision=3, scale=2) private BigDecimal avgRating;` `@Column(name="rating_count", nullable=false) private Integer ratingCount = 0;`)
- Modify: `dto/response/ProductDetailResponse.java` + `ProductSummaryResponse.java` (add `BigDecimal avgRating, Integer ratingCount` + mapper wiring)
- Create: `kafka/RatingLifecycleListenerConfig.java` (copy ShippingListenerConfig: `BaseKafkaListenerConfig<String, RatingLifecycleEvent>`, factory bean `ratingListenerFactory`)
- Create: `kafka/RatingLifecycleEvent.java` (record: eventId, eventType, occurredAt, ratingId, productId, userId, rating int, comment, verified boolean, action String, visible boolean, avgRating BigDecimal, ratingCount int)
- Create: `kafka/ProductRatingConsumer.java` (`extends BaseKafkaConsumer<String, RatingLifecycleEvent>`, `@KafkaListener(topics="shop.rating.lifecycle.v1", containerFactory="ratingListenerFactory", groupId via yml `shop.kafka.consumer.group-id: product-service` — follow shipping's group config)` → `processMessage(event, headers, this::handle)` → handler delegates to `@Transactional` `ProductRatingService.apply(event)`: unknown productId → log+return; copy avgRating/ratingCount, save)
- Test: `ProductRatingConsumerTest.java` (service-level: apply CREATED snapshot updates row; replay same event twice → same state (idempotent); unknown product → no throw no save; HIDDEN event with dropped snapshot lowers count; UPDATE event overwrites)

- [ ] **Step 1: failing tests → implement → GREEN.** **Step 2:** `./mvnw -pl product-service test` green. **Step 3: commit** `feat(product): consume rating events — denormalized avg_rating/rating_count`

### Task 9: compose + cross-module verification

**Files:**
- Modify: `docker-compose.yml` — rating-service stanza after promotion: port 8089, db envs (ratingservice), `ORDER_SERVICE_URL: http://order-service:8084`, `RATING_SERVICE_CLIENT_ID/SECRET` passthrough, kafka env, healthcheck mirroring promotion's. Product stanza: nothing new (kafka already wired).

- [ ] **Step 1:** `docker compose config -q` → exit 0. **Step 2:** verify `ServiceRoute.RATING` covers `/api/v1/ratings/**` via existing `RoutesConfigTest` (no change — run). **Step 3:** `rg '"clientId"' docker/keycloak/import/ecommerce-realm.json` — confirm rating-service client NOT in import → add ops note line in spec §4 (commit with this task). **Step 4:** full fleet compile: `./mvnw -T1C install -DskipTests -q`; then `./mvnw -pl order-service,rating-service,product-service test` all green. **Step 5: commit** `chore(compose): rating-service stanza`

### Task 10: final whole-branch review

- [ ] Dispatch reviewer subagent: whole-branch diff vs main; spec D1–D8 + §4/§5 audit; E2E hop check (submit→outbox→relay→product copy); zero-regression (only rating-service/* + order endpoint + product columns/consumer + ErrorCode/i18n/ApiPaths tails + compose + spec); security (P2-6 posture, ADMIN gates, fail-closed client, HMAC n/a). Fix rounds until APPROVED (or APPROVED-WITH-FINDINGS adjudicated).
