# QA Forensics — Raw Test Inventory (revision f242158265163d65ef73d378331d2bd7a92479ee)

Auditor: independent QA/test auditor (read-only pass, no build executed).
Scope: all 430 `src/test` java files found by glob. Classification is static (annotations, base classes,
container/wiremock/concurrency imports) — no test was executed.

Kind legend:
- `UNIT` plain JUnit/Mockito, no Spring context · `UNIT-CONC` unit test using ExecutorService/Barrier/Latch (mock-backed)
- `SLICE` `@WebMvcTest` controller slice · `REPO` `@DataJpaTest` or repository-focused Testcontainers test
- `IT` `@SpringBootTest` full context (directly annotated or extending an abstract `@SpringBootTest` base), Testcontainers
- `IT-CONC` IT that exercises real concurrency (ExecutorService/CyclicBarrier/CountDownLatch with real DB)
- `TC-UNIT` non-Spring test that starts a Testcontainers container (redis/keycloak)
- `CONTRACT` resource/changelog/i18n/config contract check · `SUPPORT` non-test helper (base class, fixture config)

Runs-under-`mvn test` column: `yes` = matched by surefire (module pom includes `**/*IT.java`, or name matches
surefire defaults `*Test/*Tests/Test*`), `NO (dead)*` = class name ends `IT` and its module pom has NO surefire
include override and no `src/it/java` (root failsafe `it` profile never activates) → silently excluded from
plain `mvn test`/`mvn verify`; reachable only via the harness suites' `-Dtest=` selector.
`@Test` = number of `@Test` methods in the module (grep count).

## Totals

| module | files | test classes | support | UNIT | SLICE | REPO | IT | of which IT-CONC | UNIT-CONC / TC-UNIT | CONTRACT | @Test methods |
|---|---|---|---|---|---|---|---|---|---|---|---|
| auth-service | 14 | 14 | 0 | 7 | 3 | 2 | 2 | 0 | 1 UNIT-CONC | 1 (RoleCatalogue) | 90 |
| favourite-service | 4 | 3 | 1 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | n/a |
| gateway-service | 30 | 29 | 1 | 20 | 0 | 0 | 5 (@SpringBootTest) | 0 | 4 TC-UNIT | 0 | n/a |
| inventory-service | 22 | 20 | 2 | 5 | 5 | 2 | 7 | 3 | 1 UNIT-CONC (mock) | 0 | 109 |
| media-service | 32 | 29 | 2 | 13 | 3 | 0 | 11 | 2 | 2 (mem-budget) | 2 (i18n) | n/a |
| notification-service | 17 | 14 | 3 | 10 | 1 | 0 | 3 | 0 | 1 UNIT-CONC | 2 (dto/entity) | n/a |
| order-service | 53 | 52 | 1 | 27 | 8 | 3 | 12 | 5 | 1 UNIT-CONC (PricingServiceImplParallelFetch) | 2 (config) | 298 |
| payment-service | 49 | 45 | 4 | 25 | 3 | 1 | 15 | 5 | 0 | 3 (db contracts) | 342 |
| product-service | 42 | 40 | 2 | 18 | 8 | 1 | 12 | 2 | 0 | 3 (i18n/mapper) | n/a |
| promotion-service | 24 | 21 | 3 | 10 | 4 | 0 | 7 | 3 | 0 | 2 (dto/i18n) | 163 |
| rating-service | 20 | 18 | 2 | 10 | 3 | 0 | 5 | 2 | 0 | 2 (dto/i18n) | n/a |
| search-service | 14 | 13 | 1 | 5 | 3 | 0 | 5 | 1 | 0 | 1 (deploy contract) | n/a |
| shipping-service | 33 | 30 | 3 | 23 | 2 | 0 | 4 | 1 | 0 | 3 (db contracts) | n/a |
| tax-service | 13 | 11 | 2 | 4 | 4 | 0 | 3 | 0 | 0 | 0 | n/a |
| utils (6 modules) | 63 | 62 | 1 | 55 | 1 | 1 | 1 (@SpringBootTest starter) | 0 | 5 UNIT-CONC | several (kafka contract/config) | n/a |
| **TOTAL** | **430** | **~406** | **~25** | | | | | | | | |

Counted infrastructure per IT bases (verified by reading the base classes): every `Abstract*IntegrationTest`
starts its own **PostgreSQLContainer(postgres:16)** + **KafkaContainer(cp-kafka:7.6.0)** and wires them via
`@DynamicPropertySource`; order-service additionally starts **redis:7-alpine** and **six WireMockServer(0)**
(product, inventory, tax, promotion, keycloak, payment); search starts **ElasticsearchContainer 9.4.5**;
gateway rate-limit tests start **redis** and one **keycloak** IT exists.

---

## auth-service (14)

| class | kind | runs under mvn test | notes |
|---|---|---|---|
| constant/RoleCatalogueContractTest | CONTRACT | yes | static role catalogue check |
| controller/AuthControllerTest | SLICE | yes | @WebMvcTest |
| controller/RoleControllerTest | SLICE | yes | @WebMvcTest |
| controller/UserControllerTest | SLICE | yes | @WebMvcTest |
| health/KeycloakPasswordGrantPostureCheckTest | UNIT | yes | config posture |
| repository/PasswordResetTokenConsumptionTest | REPO (+concurrency) | yes | @DataJpaTest + TC postgres; token double-consume race |
| repository/RoleCatalogueMigrationTest | REPO | yes | @DataJpaTest + TC; liquibase migration |
| security/SecurityFilterChainIntegrationTest | IT (mock env) | yes | @SpringBootTest MOCK; **DataSource autoconfig excluded** (no DB) |
| security/SelfServiceIdentityResolutionTest | IT | yes | @SpringBootTest + TC |
| service/impls/AuthServiceImplTest | UNIT | yes | |
| service/impls/PasswordResetEmailSenderTest | UNIT | yes | |
| service/impls/RoleServiceImplTest | UNIT | yes | |
| service/impls/UserServiceImplForgotPasswordLoggingTest | UNIT | yes | |
| service/impls/UserServiceImplTest | UNIT | yes | |

## favourite-service (4)

| class | kind | runs | notes |
|---|---|---|---|
| config/TestLiquibaseConfig | SUPPORT | n/a | fixture config |
| controller/FavouriteControllerTest | SLICE | yes | |
| repository/FavouriteRepositoryTest | REPO | yes | @DataJpaTest + TC |
| service/impls/FavouriteServiceImplTest | UNIT | yes | |

## gateway-service (30)

| class | kind | runs | notes |
|---|---|---|---|
| GatewayRateLimitContextTest | IT | yes | @SpringBootTest |
| KeycloakRealmImportIT | TC-UNIT | yes (pom includes *IT) | testcontainers keycloak |
| WebFluxIpAllowlistTests | IT | yes | @SpringBootTest + WireMock |
| WebFluxRateLimitTests | IT | yes | @SpringBootTest + redis TC + WireMock |
| WebFluxRouteTests | IT | yes | @SpringBootTest + WireMock |
| WebFluxUnauthenticatedRateLimitTests | IT | yes | @SpringBootTest + redis TC |
| config/SecurityConfigTest | UNIT | yes | |
| config/GatewayJwtSecurityConfigTest | UNIT | yes | |
| config/GatewaySecurityPostureValidatorTest | UNIT | yes | |
| config/RoutesConfigTest | UNIT | yes | |
| filter/AdminIpAllowlistFilterTest | UNIT | yes | |
| filter/AdminRoleGateFilterTest | UNIT | yes | |
| filter/ClientIpResolverTest | UNIT | yes | |
| filter/FilterOrderTest | UNIT | yes | |
| filter/GatewayErrorResponseWriterTest | UNIT | yes | |
| filter/LiteralIpAddressTest | UNIT | yes | |
| filter/PreAuthRateLimitFilterTest | UNIT | yes | |
| filter/RateLimitFilterTest | TC-UNIT | yes | redis container |
| filter/RequestPathMatcherTest | UNIT | yes | |
| ratelimit/FleetRateLimitBudgetTest | UNIT | yes | |
| ratelimit/GlobalRateLimitFilterTest | UNIT | yes | |
| ratelimit/GlobalRateLimitPropertiesTest | UNIT | yes | |
| ratelimit/RateLimitKeyResolverTest | UNIT | yes | |
| ratelimit/RateLimitPropertiesTest | UNIT | yes | |
| ratelimit/RateLimitRejectionContractTest | UNIT | yes | |
| ratelimit/RedisEdgeRateLimiterTest | TC-UNIT | yes | redis container |
| ratelimit/RedisFleetRateLimiterTest | TC-UNIT | yes | redis container |
| ratelimit/RouteRateLimitFilterTest | UNIT | yes | |
| resilience/DownstreamResilienceFilterTest | UNIT | yes | |
| support/TestKeys | SUPPORT | n/a | fixture keys |

## inventory-service (22)

| class | kind | runs | notes |
|---|---|---|---|
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/InternalInventoryControllerTest | SLICE | yes | |
| controller/InventoryControllerAuditTest | SLICE | yes | |
| controller/InventoryControllerIdempotencyKeyTest | SLICE | yes | |
| controller/InventoryControllerTest | SLICE | yes | |
| controller/ReservationStateEndpointTest | SLICE | yes | |
| repository/InventoryRepositoryTest | REPO | yes | @DataJpaTest + TC |
| repository/ReservationsOrderIdIndexIT | REPO | **NO (dead)*** | @DataJpaTest + TC; verifies index on reservations(order_id); module pom has no surefire include → excluded from plain mvn test/verify (QA-1) |
| service/InventoryLifecycleIdempotencyTest | IT | yes | extends AbstractIntegrationTest (PG+Kafka TC) |
| service/InventoryOutboxAggregateOrderIT | IT-CONC | yes | outbox per-aggregate ordering under ExecutorService |
| service/InventoryOutboxClaimConcurrencyTest | IT-CONC | yes | FOR UPDATE claim race |
| service/InventoryOutboxRelayConnectionTest | UNIT | yes | relay failure-mode unit |
| service/InventoryOutboxRelayIntegrationTest | IT | yes | |
| service/InventoryOutboxRelayRecoveryIT | IT | yes | stranded-claim recovery |
| service/InventoryOutboxRelayTest | UNIT | yes | |
| service/InventoryReserveIdempotencyTest | IT-CONC | yes | **the** oversell/idempotency evidence: same-key replay, 2-thread same-key race, 2-thread last-unit race, commit/release idempotency, sweeper, DB unique constraint |
| service/ReleaseCommittedTest | IT | yes | release-committed endpoint semantics |
| service/ReservationCleanupSchedulerTest | UNIT | yes | |
| service/impls/InventoryServiceImplReserveConcurrencyTest | UNIT-CONC (weak) | yes | 50 threads against **mocks**; atomicReserve stubbed to 1; no DB, no quantity assertions (QA-5) |
| service/impls/InventoryServiceImplTest | UNIT | yes | |
| service/impls/ReservationServiceImplTest | UNIT | yes | retry wrapper logic only |
| support/AbstractIntegrationTest | SUPPORT | n/a | @SpringBootTest base; PG+Kafka TC |

## media-service (32)

| class | kind | runs | notes |
|---|---|---|---|
| support/AbstractMediaIntegrationTest | SUPPORT | n/a | @SpringBootTest base PG+Kafka |
| support/TestImages | SUPPORT | n/a | |
| client/MediaReferenceClientIT | IT | yes | WireMock |
| controller/BackofficeMediaControllerAuditTest | SLICE | yes | |
| controller/BackofficeMediaControllerTest | SLICE | yes | |
| controller/MediaControllerIT | IT | yes | |
| controller/MediaPublicControllerTest | SLICE | yes | |
| controller/MediaUploadMemoryBudgetIT | IT | yes | memory-budget IT |
| i18n/MediaErrorCodeI18nCoverageTest | CONTRACT | yes | |
| i18n/MediaI18nKeysTest | CONTRACT | yes | |
| job/MediaPurgeIT | IT | yes | WireMock |
| job/MediaPurgeJobTest | UNIT | yes | |
| metrics/MediaMetricsTest | UNIT | yes | |
| outbox/MediaEventServiceTest | UNIT | yes | |
| outbox/MediaOutboxAggregateOrderIT | IT-CONC | yes | |
| outbox/MediaOutboxClaimConcurrencyIT | IT-CONC | yes | |
| outbox/MediaOutboxIT | IT | yes | |
| outbox/MediaOutboxRelayConnectionTest | UNIT | yes | |
| outbox/MediaOutboxRelayRecoveryIT | IT | yes | |
| outbox/MediaOutboxRelayTest | UNIT | yes | |
| outbox/MediaOutboxRetentionIT | IT | yes | |
| outbox/MediaOutboxRetentionSchedulerTest | UNIT | yes | |
| service/impls/MediaBucketUnificationTest | UNIT | yes | |
| service/impls/MediaFormatsTest | UNIT | yes | |
| service/impls/MediaLifecycleServiceImplTest | UNIT | yes | |
| service/impls/MediaQueryServiceImplTest | UNIT | yes | |
| service/impls/MediaUploadServiceImplTest | UNIT | yes | |
| service/impls/UploadIT | IT | yes | |
| service/impls/VariantRendererMemoryBudgetTest | UNIT | yes | |
| service/impls/VariantRendererObjectCountTest | UNIT | yes | |
| storage/BucketBootstrapTest | UNIT | yes | |
| storage/ProvisioningIT | IT | yes | |

## notification-service (17)

| class | kind | runs | notes |
|---|---|---|---|
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeNotificationControllerTest | SLICE | yes | |
| dto/OrderLifecycleEventTest | UNIT | yes | |
| entity/NotificationEntityTest | UNIT | yes | |
| NotificationFlowIT | IT | yes | extends AbstractIntegrationTest |
| scheduler/NotificationRetryIT | IT | yes | |
| scheduler/NotificationRetrySchedulerTest | UNIT | yes | |
| service/NotificationDeliveryServiceTest | UNIT | yes | |
| service/NotificationRetryPolicyTest | UNIT | yes | |
| service/NotificationTemplatesTest | UNIT | yes | |
| service/NotificationWriterTest | UNIT | yes | |
| service/impls/NotificationServiceImplTest | UNIT-CONC | yes | bounded-concurrency send |
| service/sender/NotificationFailureClassifierTest | UNIT | yes | |
| service/sender/NotificationSenderConfigTest | UNIT | yes | |
| service/sender/SmtpNotificationSenderTest | UNIT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka |
| support/NotificationBootstrapIT | IT | yes | |

## order-service (53) — @Test methods: 298

| class | kind | runs | notes |
|---|---|---|---|
| client/PaymentServiceClientTest | UNIT | yes | |
| client/ProductServiceClientTest | UNIT | yes | |
| config/CacheSerializerRoundTripTest | UNIT | yes | |
| config/PaymentRefundTimeoutTest | UNIT | yes | |
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeOrderReturnControllerTest | SLICE | yes | |
| controller/CartControllerTest | SLICE | yes | |
| controller/ConfirmOrchestrationWebMvcTest | SLICE | yes | |
| controller/OrderControllerTest | SLICE | yes | |
| controller/OrderReturnControllerTest | SLICE | yes | |
| controller/OrderStatusControllerAuditTest | SLICE | yes | |
| controller/OrderTrackingControllerTest | SLICE | yes | |
| controller/VerifyPurchaseEndpointTest | SLICE | yes | |
| exception/DownstreamCircuitBreakerExceptionHandlerTest | UNIT | yes | |
| kafka/PaymentLifecycleConsumerTest | UNIT | yes | consumer dispatch; idempotent confirm key |
| kafka/ShippingDeliveredConsumerIT | IT | yes | |
| kafka/ShippingDeliveredConsumerTest | UNIT | yes | |
| repository/CartRepositoryTest | REPO | yes | @DataJpaTest + TC |
| repository/OrderItemRepositoryTest | REPO | yes | @DataJpaTest + TC |
| repository/OrderOutboxClaimConcurrencyIT | IT-CONC | yes | 2-thread FOR UPDATE claim; locked peer gets empty; second row while head locked |
| repository/OrderRepositoryTest | REPO | yes | @DataJpaTest + TC |
| service/CancelOutsideTransactionIT | IT-CONC | yes | hikari pool=1; cancel outside tx |
| service/CompensationFailureLedgerIT | IT | yes | WireMock failures → ledger |
| service/ConfirmCompensationLedgerIT | IT-CONC | yes | confirm compensation under downstream failure |
| service/ConfirmOrchestrationIT | IT-CONC | yes | confirm vs release-committed ordering, duplicate confirm, concurrent confirm, idempotency row; WireMock call-count assertions on all downstreams |
| service/IdempotencyClaimIT | IT-CONC | yes | 25 iterations × 2-thread claim race on idempotency_keys insert-if-absent |
| service/OrderCommitCoordinatorTest | UNIT | yes | |
| service/OrderConfirmMetricsTest | UNIT | yes | |
| service/OrderCreationSagaIntegrationTest | IT | yes | WireMock downstreams |
| service/OrderMetricsTest | UNIT | yes | |
| service/OrderReconciliationSchedulerChunkedTest | UNIT | yes | |
| service/OrderReconciliationSchedulerMemoizedCountTest | UNIT | yes | |
| service/OrderReconciliationSchedulerTest | UNIT | yes | |
| service/ShippingDeliveredHandlerTest | UNIT | yes | |
| service/impls/CartClearLockIT | IT-CONC | yes | cart FOR UPDATE lock blocks clear |
| service/impls/CartServiceImplTest | UNIT | yes | |
| service/impls/GuestCartCleanupSchedulerTest | UNIT | yes | |
| service/impls/IdempotencyServiceImplTest | UNIT | yes | |
| service/impls/OrderCreateSagaTest | UNIT | yes | saga compensation/rollback with mocks |
| service/impls/OrderOutboxRelayConnectionTest | UNIT | yes | |
| service/impls/OrderOutboxRelayRecoveryIT | IT | yes | |
| service/impls/OrderOutboxRelayTest | UNIT | yes | |
| service/impls/OrderReturnRefundConvergenceTest | UNIT | yes | |
| service/impls/OrderReturnServiceTest | UNIT | yes | |
| service/impls/OrderServiceImplCancelConfirmedTest | UNIT | yes | |
| service/impls/OrderServiceImplTest | UNIT | yes | 617 lines; state + verify assertions |
| service/impls/OrderShippedCancellationIT | IT | yes | |
| service/impls/OrderStatusServiceImplTest | UNIT | yes | |
| service/impls/OrderTrackingAbuseBrakeIT | IT | yes | |
| service/impls/OrderTrackingAuthorizationTest | UNIT | yes | |
| service/impls/PricingServiceImplParallelFetchTest | UNIT-CONC | yes | parallel price fetch |
| service/impls/PricingServiceImplTest | UNIT | yes | |
| support/AbstractOrderServiceIT | SUPPORT | n/a | @SpringBootTest RANDOM_PORT; PG+Kafka+redis TC; 6 WireMock servers; **security disabled in ITs** |

## payment-service (49) — @Test methods: 342

| class | kind | runs | notes |
|---|---|---|---|
| CapturedPaymentLookupIT | IT | yes | |
| PaymentCaptureConfirmationIT | IT | yes | scripted provider; capture/refund/indeterminate |
| PaymentFlowIT | IT | yes | create→capture→signed webhook→refund; replay dedup; bad signature; amount mismatch |
| PaymentIdempotencyIT | IT-CONC | yes | same-key replay + conflicts + 2-thread create race |
| RefundConcurrencyIT | IT-CONC | yes | 2-thread refund race; provider reached once; partial refunds; approval replay |
| StripeWebhookIT | IT (@SpringBootTest direct) | yes | provider=stripe, real HMAC signing, tamper 401, replay dedup, MockedStatic Stripe SDK |
| WebhookRefundLedgerIT | IT | yes | provider refund events vs platform ledger |
| config/PaymentProviderProductionGuardTest | UNIT | yes | production provider guard |
| config/PaymentStripeLiveKeyGuardTest | UNIT | yes | live-key guard |
| config/PaymentStripePropertiesTest | UNIT | yes | |
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| db/PaymentCurrencyChangelogContractTest | CONTRACT | yes | |
| db/PaymentCurrencyChangelogIT | CONTRACT | yes | changelog assertions (no server) |
| db/PaymentStatusColumnWidthContractTest | CONTRACT | yes | |
| outbox/OutboxRetentionSchedulerTest | UNIT | yes | |
| outbox/PaymentOutboxAggregateOrderIT | IT-CONC | yes | |
| outbox/PaymentOutboxBatchAsyncIT | IT | yes | |
| outbox/PaymentOutboxClaimConcurrencyIT | IT-CONC | yes | |
| outbox/PaymentOutboxPartitionOrderIT | IT | yes | |
| outbox/PaymentOutboxRelayConnectionTest | UNIT | yes | |
| outbox/PaymentOutboxRelayOrderTest | UNIT | yes | |
| outbox/PaymentOutboxRelayRecoveryIT | IT | yes | |
| outbox/PaymentOutboxStrandedClaimIT | IT | yes | |
| provider/MockProviderTest | UNIT | yes | trivial provider smoke |
| provider/MoMoProviderTest | UNIT | yes | mocked HTTP |
| provider/VNPayProviderTest | UNIT | yes | mocked HTTP |
| provider/PaymentProviderConfigTest | UNIT | yes | |
| provider/PaymentProviderRegistrationTest | UNIT | yes | |
| provider/PaymentProviderRoutingTest | UNIT | yes | |
| provider/StripeHealthIndicatorTest | UNIT | yes | |
| provider/StripeProviderTest | UNIT | yes | MockedStatic<PaymentIntent/Refund>; param captors |
| repository/PaymentRepositoryIdempotencyScopingTest | REPO | yes | |
| scheduler/WebhookRetrySchedulerTest | UNIT | yes | |
| service/PaymentStateMachineTest | UNIT | yes | |
| service/PaymentWriterTest | UNIT | yes | |
| service/ReceiptServiceTest | UNIT | yes | |
| service/WebhookEventServiceStripeTest | UNIT | yes | real HMAC; forged/tampered/unconfigured rejects; retryable failures |
| service/WebhookEventServiceTest | UNIT | yes | |
| service/impls/PaymentServiceImplTest | UNIT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka; provider=mock; fake storage bean |
| support/PaymentBootstrapIT | IT | yes | liquibase bootstrap |
| support/ScriptedPaymentProvider | SUPPORT | n/a | scriptable fake PSP |
| support/WebhookRetryIndexIT | IT | yes | |
| webhook/PaymentWebhookControllerTest | SLICE | yes | |
| webhook/PspCallbackControllerTest | SLICE | yes | |
| webhook/WebhookSignatureVerifierTest | UNIT | yes | real HmacSHA256 incl. Stripe t=/v1 + timestamp window |

## product-service (42)

| class | kind | runs | notes |
|---|---|---|---|
| client/InventoryReservationClientTest | UNIT | yes | |
| client/MediaHeadClientTest | UNIT | yes | |
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeBrandControllerTest | SLICE | yes | |
| controller/BackofficeCategoryControllerTest | SLICE | yes | |
| controller/BackofficeProductControllerTest | SLICE | yes | |
| controller/BrandControllerTest | SLICE | yes | |
| controller/InternalProductMediaControllerTest | SLICE | yes | |
| controller/MediaHeadValidationIT | IT | yes | WireMock |
| controller/ProductControllerAuditTest | SLICE | yes | |
| controller/ProductControllerTest | SLICE | yes | |
| controller/ProductVariantControllerTest | SLICE | yes | |
| i18n/ProductI18nKeysTest | CONTRACT | yes | |
| job/ProductMediaSweepJobIT | IT | yes | WireMock |
| job/ProductMediaSweepJobTest | UNIT | yes | |
| kafka/MediaDeletedConsumerIT | IT | yes | |
| kafka/MediaDeletedConsumerTest | UNIT | yes | |
| kafka/ProductRatingConsumerTest | UNIT | yes | |
| kafka/RatingLifecycleConsumerIT | IT | yes | |
| mapper/ProductMapperMediaFieldsTest | CONTRACT | yes | |
| mapper/ProductMapperRatingFieldsTest | CONTRACT | yes | |
| repository/ProductRepositoryTest | REPO | yes | @DataJpaTest + TC |
| service/OutboxRelayConnectionTest | UNIT | yes | |
| service/OutboxRelayIntegrationTest | IT | yes | extends Abstract |
| service/OutboxRelayRecoveryIT | IT | yes | |
| service/OutboxRelayTest | UNIT | yes | |
| service/ProductCurrencyRegressionIT | IT | yes | |
| service/ProductMediaServiceTest | UNIT | yes | |
| service/ProductOutboxAggregateOrderIT | IT-CONC | yes | |
| service/ProductOutboxClaimConcurrencyIT | IT-CONC | yes | |
| service/ProductRatingOutboxIntegrationTest | IT | yes | extends Abstract |
| service/ProductRatingServiceTest | UNIT | yes | |
| service/ProductServiceFilterSpecIT | IT | yes | |
| service/ProductVariantServiceTest | UNIT | yes | |
| service/TransactionalProductEventPublisherTest | UNIT | yes | |
| service/impls/BrandServiceImplTest | UNIT | yes | |
| service/impls/CategoryServiceImplTest | UNIT | yes | |
| service/impls/ProductServiceImplDeleteGuardTest | UNIT | yes | |
| service/impls/ProductServiceImplMediaClearTest | UNIT | yes | |
| service/impls/ProductServiceImplTest | UNIT | yes | |
| service/impls/ProductServiceImplTransactionBoundaryIT | IT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka |

## promotion-service (24) — @Test methods: 163

| class | kind | runs | notes |
|---|---|---|---|
| config/PromotionMessageBundleConfigurationTest | UNIT | yes | |
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeCampaignAuditTest | SLICE | yes | |
| controller/BackofficeCampaignControllerTest | SLICE | yes | |
| controller/PromotionReservationAuditTest | SLICE | yes | |
| controller/PromotionReservationControllerTest | SLICE | yes | |
| dto/request/CampaignRequestValidationTest | CONTRACT | yes | bean validation |
| i18n/PromotionI18nKeysTest | CONTRACT | yes | |
| service/CampaignLifecycleTest | UNIT | yes | |
| service/CampaignQuotaConcurrencyIT | IT-CONC | yes | 2-thread last-coupon race; per-user limit; used_count asserted in DB |
| service/CampaignReserveTest | UNIT | yes | reserve rules with mocks (repo-backed stubs) |
| service/CampaignServiceTest | UNIT | yes | |
| service/DiscountCalculatorTest | UNIT | yes | pure math |
| service/LifecycleAndEventsIT | IT | yes | |
| service/PromotionOutboxAggregateOrderIT | IT-CONC | yes | |
| service/PromotionOutboxClaimConcurrencyIT | IT-CONC | yes | |
| service/PromotionOutboxRelayConnectionTest | UNIT | yes | |
| service/PromotionOutboxRelayRecoveryIT | IT | yes | |
| service/PromotionOutboxRelayTest | UNIT | yes | |
| service/ReservationCleanupSchedulerTest | UNIT | yes | |
| service/ReserveFlowIT | IT-CONC | yes | 8-thread per_user_limit=3 race; budget exhaustion; expiry quota return |
| service/impls/ReservationRetryServiceImplTest | UNIT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka |
| support/PromotionBootstrapIT | IT | yes | liquibase bootstrap |

## rating-service (20)

| class | kind | runs | notes |
|---|---|---|---|
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeRatingAuditTest | SLICE | yes | |
| controller/BackofficeRatingControllerTest | SLICE | yes | |
| controller/StorefrontRatingControllerTest | SLICE | yes | |
| dto/RatingSubmitValidationTest | CONTRACT | yes | |
| eligibility/EligibilityClientTest | UNIT | yes | |
| entity/RatingMappingIT | IT | yes | |
| i18n/RatingI18nKeysTest | CONTRACT | yes | |
| metrics/RatingMetricsTest | UNIT | yes | |
| outbox/RatingOutboxAggregateOrderIT | IT-CONC | yes | |
| outbox/RatingOutboxClaimConcurrencyIT | IT-CONC | yes | |
| outbox/RatingOutboxRelayConnectionTest | UNIT | yes | |
| outbox/RatingOutboxRelayRecoveryIT | IT | yes | |
| outbox/RatingOutboxRelayTest | UNIT | yes | |
| service/RatingEventServiceTest | UNIT | yes | |
| service/impls/RatingServiceImplEditTest | UNIT | yes | |
| service/impls/RatingServiceImplModerationTest | UNIT | yes | |
| service/impls/RatingServiceImplTest | UNIT | yes | |
| service/impls/RatingServiceImplTransactionBoundaryIT | IT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka |

## search-service (14)

| class | kind | runs | notes |
|---|---|---|---|
| client/ProductBackofficeClientTest | UNIT | yes | |
| config/ElasticsearchConfigTest | UNIT | yes | |
| controller/BackofficeSearchAuditTest | SLICE | yes | |
| controller/BackofficeSearchControllerTest | SLICE | yes | |
| controller/SearchControllerTest | SLICE | yes | |
| deploy/DeploymentCredentialContractTest | CONTRACT | yes | |
| kafka/ProductSearchIngestionIT | IT | yes | ES container |
| kafka/ProductSearchMonotonicProjectionIT | IT | yes | ES container |
| search/SearchIndexProvisioningIT | IT | yes | ES container |
| service/impls/ReindexIT | IT-CONC | yes | WireMock + ExecutorService |
| service/impls/ReindexServiceTest | UNIT | yes | |
| service/impls/SearchQueryIT | IT | yes | ES container |
| service/impls/SearchQueryServiceTest | UNIT | yes | |
| support/AbstractSearchIntegrationTest | SUPPORT | n/a | @SpringBootTest base; ElasticsearchContainer 9.4.5 |

## shipping-service (33)

| class | kind | runs | notes |
|---|---|---|---|
| ShippingFlowIT | IT | yes | extends Abstract |
| carrier/CarrierAdapterDraftsTest | UNIT | yes | |
| carrier/CarrierConfigTest | UNIT | yes | |
| carrier/CarrierRestClientCustomizerTest | UNIT | yes | |
| carrier/CarrierShipmentClientTest | UNIT | yes | |
| carrier/GhnCarrierAdapterTest | UNIT | yes | |
| carrier/GhtkCarrierAdapterTest | UNIT | yes | |
| config/TestClockConfig | SUPPORT | n/a | |
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeShipmentAuditTest | SLICE | yes | |
| controller/BackofficeShipmentControllerTest | SLICE | yes | |
| db/ShipmentEventStatusChangelogTest | CONTRACT | yes | |
| db/ShippingChangelogContractTest | CONTRACT | yes | |
| db/ShippingChangelogIT | CONTRACT | yes | changelog assertions (pom includes *IT → runs) |
| outbox/ShippingEventPublisherImplTest | UNIT | yes | |
| outbox/ShippingOutboxClaimConcurrencyIT | IT-CONC | yes | |
| outbox/ShippingOutboxRelayConnectionTest | UNIT | yes | |
| outbox/ShippingOutboxRelayRecoveryIT | IT | yes | |
| outbox/ShippingOutboxRelayTest | UNIT | yes | |
| outbox/ShippingOutboxRetentionSchedulerTest | UNIT | yes | |
| scheduler/ReconciliationSchedulerTest | UNIT | yes | |
| scheduler/ShipmentCreationRetrySchedulerTest | UNIT | yes | |
| scheduler/WebhookRetrySchedulerTest | UNIT | yes | |
| service/ShipmentCreationServiceTest | UNIT | yes | |
| service/ShipmentStateMachineTest | UNIT | yes | |
| service/ShipmentStatusWriteGuardTest | UNIT | yes | |
| service/ShipmentWriterTest | UNIT | yes | |
| service/WebhookEventWriterTest | UNIT | yes | |
| service/impls/ShipmentServiceImplTest | UNIT | yes | |
| service/impls/WebhookEventServiceTest | UNIT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka |
| support/ShippingBootstrapIT | IT | yes | |
| webhook/WebhookSignatureVerifierTest | UNIT | yes | real HMAC |

## tax-service (13)

| class | kind | runs | notes |
|---|---|---|---|
| config/TestLiquibaseConfig | SUPPORT | n/a | |
| controller/BackofficeTaxAuditTest | SLICE | yes | |
| controller/BackofficeTaxClassControllerTest | SLICE | yes | |
| controller/BackofficeTaxRateControllerTest | SLICE | yes | |
| controller/TaxCalculationControllerTest | SLICE | yes | |
| service/TaxCalculationIT | IT | yes | extends Abstract |
| service/TaxCalculatorTest | UNIT | yes | pure math |
| service/TaxCatalogIT | IT | yes | |
| service/impls/TaxCalculationServiceImplTest | UNIT | yes | |
| service/impls/TaxClassServiceImplTest | UNIT | yes | |
| service/impls/TaxRateServiceImplTest | UNIT | yes | |
| support/AbstractIntegrationTest | SUPPORT | n/a | PG+Kafka |
| support/TaxBootstrapIT | IT | yes | |

## utils (63 files across common-core/common-kafka/common-keycloak/common-logging/common-security/common-spring/common-storage)

All UNIT unless noted. UNIT-CONC: `common-kafka/producer/KafkaMessagePublisherTest`,
`common-kafka/replay/DeadLetterReplayOffsetTest`, `common-keycloak/client/KeycloakAdminClientTokenConcurrencyTest`,
`common-logging/audit/BoundedAsyncAuditEventWriterTest`, `common-storage/config/ObjectStorageStartupResilienceTest`.
Notables: `common-core/data/AbstractMappedEntityTest` (@DataJpaTest, no TC), `common-spring/CommonLibraryStarterTests`
(@SpringBootTest context test), `common-spring/test/TestSecurityConfig` (SUPPORT), kafka framework suite
(dead-letter, schema evolution, typed bind, group-id contract, topic contract, replay endpoints), audit aspect/writer,
storage leak test. Self-skipping assumption tests: `common-kafka/contract/KafkaTopicContractTest` (:32,:93),
`common-kafka/config/KafkaPropertiesTopicsRemovedTest` (:26), `common-kafka/consumer/KafkaListenerGroupIdContractTest` (:26),
`common-spring/deploy/ProductionEnvTemplateTest` (:32,:38,:64).

---

### Support/fixture files counted (25 total)

9× `TestLiquibaseConfig` (favourite, promotion, notification, tax, payment, order, inventory, rating, product),
11× `Abstract*IntegrationTest`/`AbstractOrderServiceIT`/`AbstractSearchIntegrationTest`/`AbstractMediaIntegrationTest`
(inventory, media, notification, order, payment, product, promotion, rating, search, shipping, tax),
`shipping TestClockConfig`, `gateway TestKeys`, `media TestImages`, `payment ScriptedPaymentProvider`,
`common-spring TestSecurityConfig`.

### Datasource reality (task 4 evidence)

- No service has any `src/test/resources/application*.yml` — the only test-scope yaml in the repo is
  `utils/common-spring/src/test/resources/application.yml` (no datasource entries).
- Every DB/Kafka/Redis/ES dependency is injected by `@DynamicPropertySource` from Testcontainers
  (e.g. inventory AbstractIntegrationTest.java:41-55, promotion :35-50, payment :47-63, order :85-109,
  search AbstractSearchIntegrationTest.java:44-45).
- Main configs default to the compose DB (`order-service/src/main/resources/application.yml:8`
  `jdbc:postgresql://localhost:5432/orderservice`) but no test consumes that default: every context-loading
  test overrides it; `auth-service/.../SecurityFilterChainIntegrationTest.java:38-44` excludes DataSource
  autoconfiguration outright. **No test reads the developer's compose database.**
