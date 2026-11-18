# Production Audit — catalog / search / media (A11 Search·Media Specialist, A2 DDD Architect)

- **Repository:** `/Users/tonminh-mac/IdeaProjects/untitled5`
- **Git revision audited:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f` (`git rev-parse HEAD`)
- **Scope:** product-service, search-service, media-service, promotion-service, tax-service, rating-service, favourite-service, notification-service — every `.java` file (main + test), every `db/changelog` YAML, every `application.yml`.
- **Read-only audit.** No application source file was modified. The only artefact written is this report.
- **Total files read:** 430 (393 `.java` + 37 YAML/YML). **Total lines read:** 38,177.
- **Untracked files in scope:** 0 (every file below is tracked at the audited revision).

**How to read this report.** Every claim is anchored to `file:line` at the audited revision. Where the code is *correct*, this is stated explicitly with the same rigour as the defects. Findings are grouped as `CAT-nn` defects in §12 with severity, evidence, violated invariant, root cause, fix, reproduction test and acceptance criterion.

---

# 1. FILE INVENTORY

Every file in scope was opened and read **in full**. The `Read range` column is `full` for all 430 entries — there are **no silent skips**. `git rev-parse HEAD:<path>` is truncated to 12 hex chars for readability; the full 40-char object name is available via the same command.

Per-service breakdown (files / lines):

| Service | main java | test java | yaml/yml | files | lines |
|---|---|---|---|---|---|
| product-service | 75 | 39 | 8 | 122 | 9,695 |
| search-service | 24 | 11 | 1 | 36 | 3,417 |
| media-service | 35 | 30 | 6 | 71 | 7,289 |
| promotion-service | 32 | 19 | 7 | 58 | 6,009 |
| tax-service | 22 | 13 | 3 | 38 | 2,430 |
| rating-service | 22 | 18 | 4 | 44 | 3,638 |
| favourite-service | 9 | 4 | 3 | 16 | 838 |
| notification-service | 23 | 17 | 5 | 45 | 3,304 |
| **Total** | **242** | **151** | **37** | **430** | **38,177** |

| # | Path | git rev-parse HEAD:<path> | Lines | Read range |
|---|---|---|---|---|
| 1 | `product-service/src/main/java/com/shop/productservice/ProductServiceApplication.java` | `3d660009145e` | 17 | full |
| 2 | `product-service/src/main/java/com/shop/productservice/client/InventoryReservationClient.java` | `c620893e13dd` | 76 | full |
| 3 | `product-service/src/main/java/com/shop/productservice/client/MediaHeadClient.java` | `0939cb31b99e` | 63 | full |
| 4 | `product-service/src/main/java/com/shop/productservice/config/CacheConfig.java` | `32520ec9132b` | 94 | full |
| 5 | `product-service/src/main/java/com/shop/productservice/config/InventoryClientConfig.java` | `23c120797e75` | 42 | full |
| 6 | `product-service/src/main/java/com/shop/productservice/config/InventoryClientProperties.java` | `4c896efd0e08` | 24 | full |
| 7 | `product-service/src/main/java/com/shop/productservice/config/MediaClientConfig.java` | `073da76700dc` | 59 | full |
| 8 | `product-service/src/main/java/com/shop/productservice/config/MediaClientProperties.java` | `4fc35af2328b` | 28 | full |
| 9 | `product-service/src/main/java/com/shop/productservice/config/ProductMediaSweepProperties.java` | `00cc13a0217a` | 29 | full |
| 10 | `product-service/src/main/java/com/shop/productservice/constant/ProductStatus.java` | `a9d43ef40f5a` | 5 | full |
| 11 | `product-service/src/main/java/com/shop/productservice/controller/BackofficeBrandController.java` | `d00477489eba` | 54 | full |
| 12 | `product-service/src/main/java/com/shop/productservice/controller/BackofficeCategoryController.java` | `3598c93ec70f` | 54 | full |
| 13 | `product-service/src/main/java/com/shop/productservice/controller/BackofficeProductController.java` | `5bc2b151de99` | 83 | full |
| 14 | `product-service/src/main/java/com/shop/productservice/controller/BackofficeProductVariantController.java` | `826986f300b8` | 76 | full |
| 15 | `product-service/src/main/java/com/shop/productservice/controller/BrandController.java` | `e27af3d507e6` | 40 | full |
| 16 | `product-service/src/main/java/com/shop/productservice/controller/CategoryController.java` | `8ef9921a07f9` | 42 | full |
| 17 | `product-service/src/main/java/com/shop/productservice/controller/InternalProductMediaController.java` | `8dc5ccc65be1` | 33 | full |
| 18 | `product-service/src/main/java/com/shop/productservice/controller/ProductController.java` | `9f7170910304` | 58 | full |
| 19 | `product-service/src/main/java/com/shop/productservice/controller/ProductVariantController.java` | `a2767db3a0fe` | 33 | full |
| 20 | `product-service/src/main/java/com/shop/productservice/dto/ProductFilter.java` | `2d30149145b5` | 6 | full |
| 21 | `product-service/src/main/java/com/shop/productservice/dto/request/BrandCreateRequest.java` | `610349c494f3` | 10 | full |
| 22 | `product-service/src/main/java/com/shop/productservice/dto/request/BrandUpdateRequest.java` | `c4f3c7a60fb4` | 10 | full |
| 23 | `product-service/src/main/java/com/shop/productservice/dto/request/CategoryCreateRequest.java` | `1e37d870a96d` | 12 | full |
| 24 | `product-service/src/main/java/com/shop/productservice/dto/request/CategoryUpdateRequest.java` | `8fc7ccbc7370` | 12 | full |
| 25 | `product-service/src/main/java/com/shop/productservice/dto/request/ProductCreateRequest.java` | `c333c9818c6c` | 53 | full |
| 26 | `product-service/src/main/java/com/shop/productservice/dto/request/ProductUpdateRequest.java` | `114e00f02cbd` | 43 | full |
| 27 | `product-service/src/main/java/com/shop/productservice/dto/request/ProductVariantCreateRequest.java` | `0d5d154a733c` | 33 | full |
| 28 | `product-service/src/main/java/com/shop/productservice/dto/request/ProductVariantUpdateRequest.java` | `af3e0a1e2b6d` | 27 | full |
| 29 | `product-service/src/main/java/com/shop/productservice/dto/response/BrandResponse.java` | `f4245bb1f6c5` | 10 | full |
| 30 | `product-service/src/main/java/com/shop/productservice/dto/response/CategoryResponse.java` | `8ddde685d83c` | 10 | full |
| 31 | `product-service/src/main/java/com/shop/productservice/dto/response/CategoryTreeResponse.java` | `3ceaf0adec32` | 12 | full |
| 32 | `product-service/src/main/java/com/shop/productservice/dto/response/MediaReferenceCountResponse.java` | `acdd72f7eef7` | 16 | full |
| 33 | `product-service/src/main/java/com/shop/productservice/dto/response/ProductDetailResponse.java` | `871fadcd8880` | 29 | full |
| 34 | `product-service/src/main/java/com/shop/productservice/dto/response/ProductSummaryResponse.java` | `5502d9073227` | 19 | full |
| 35 | `product-service/src/main/java/com/shop/productservice/dto/response/ProductVariantResponse.java` | `3117aef167a1` | 18 | full |
| 36 | `product-service/src/main/java/com/shop/productservice/entity/Brand.java` | `a5da43131ebc` | 34 | full |
| 37 | `product-service/src/main/java/com/shop/productservice/entity/Category.java` | `c8a2dab61c47` | 43 | full |
| 38 | `product-service/src/main/java/com/shop/productservice/entity/OutboxEvent.java` | `aa84200e597a` | 54 | full |
| 39 | `product-service/src/main/java/com/shop/productservice/entity/Product.java` | `66f603a279a1` | 77 | full |
| 40 | `product-service/src/main/java/com/shop/productservice/entity/ProductVariant.java` | `e9181708a8af` | 59 | full |
| 41 | `product-service/src/main/java/com/shop/productservice/job/ProductMediaSweepJob.java` | `aea771366942` | 66 | full |
| 42 | `product-service/src/main/java/com/shop/productservice/kafka/MediaDeletedConsumer.java` | `d3188d30e063` | 123 | full |
| 43 | `product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleEvent.java` | `941ad4fb1af2` | 17 | full |
| 44 | `product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleListenerConfig.java` | `52a530281078` | 44 | full |
| 45 | `product-service/src/main/java/com/shop/productservice/kafka/ProductRatingConsumer.java` | `1c24fedf0b9e` | 27 | full |
| 46 | `product-service/src/main/java/com/shop/productservice/kafka/RatingLifecycleEvent.java` | `a2ee37fedbfd` | 29 | full |
| 47 | `product-service/src/main/java/com/shop/productservice/kafka/RatingLifecycleListenerConfig.java` | `447065459db7` | 26 | full |
| 48 | `product-service/src/main/java/com/shop/productservice/mapper/BrandMapper.java` | `fe216c02d814` | 40 | full |
| 49 | `product-service/src/main/java/com/shop/productservice/mapper/CategoryMapper.java` | `0f675ec18224` | 54 | full |
| 50 | `product-service/src/main/java/com/shop/productservice/mapper/ProductMapper.java` | `02aae1ccf45b` | 101 | full |
| 51 | `product-service/src/main/java/com/shop/productservice/repository/BrandRepository.java` | `d2695e6b0b5e` | 12 | full |
| 52 | `product-service/src/main/java/com/shop/productservice/repository/CategoryRepository.java` | `298ddad276cb` | 15 | full |
| 53 | `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java` | `fdf67fb5f640` | 98 | full |
| 54 | `product-service/src/main/java/com/shop/productservice/repository/ProductRepository.java` | `499adcdfa9d6` | 50 | full |
| 55 | `product-service/src/main/java/com/shop/productservice/repository/ProductVariantRepository.java` | `f9df5871ed9b` | 21 | full |
| 56 | `product-service/src/main/java/com/shop/productservice/security/ServiceTokenProvider.java` | `a7bfd4c82c6a` | 125 | full |
| 57 | `product-service/src/main/java/com/shop/productservice/service/BrandEventPublisher.java` | `f6c5365deba3` | 12 | full |
| 58 | `product-service/src/main/java/com/shop/productservice/service/BrandService.java` | `9b58374309a2` | 21 | full |
| 59 | `product-service/src/main/java/com/shop/productservice/service/CategoryEventPublisher.java` | `a630982bd353` | 12 | full |
| 60 | `product-service/src/main/java/com/shop/productservice/service/CategoryService.java` | `f1bdd73eedaa` | 23 | full |
| 61 | `product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java` | `bfab1f596532` | 258 | full |
| 62 | `product-service/src/main/java/com/shop/productservice/service/OutboxRetentionScheduler.java` | `584f4ca61362` | 45 | full |
| 63 | `product-service/src/main/java/com/shop/productservice/service/ProductEventPublisher.java` | `f2e3b0532797` | 11 | full |
| 64 | `product-service/src/main/java/com/shop/productservice/service/ProductMediaService.java` | `84fadd4bab0e` | 74 | full |
| 65 | `product-service/src/main/java/com/shop/productservice/service/ProductMetrics.java` | `e262dcd9d480` | 78 | full |
| 66 | `product-service/src/main/java/com/shop/productservice/service/ProductRatingService.java` | `be77c451f981` | 39 | full |
| 67 | `product-service/src/main/java/com/shop/productservice/service/ProductService.java` | `3f1246f9d18a` | 32 | full |
| 68 | `product-service/src/main/java/com/shop/productservice/service/ProductVariantService.java` | `3000aed68b4c` | 21 | full |
| 69 | `product-service/src/main/java/com/shop/productservice/service/impls/BrandServiceImpl.java` | `3a21f98f8ab8` | 92 | full |
| 70 | `product-service/src/main/java/com/shop/productservice/service/impls/CategoryServiceImpl.java` | `f54fd8f011d6` | 156 | full |
| 71 | `product-service/src/main/java/com/shop/productservice/service/impls/ProductServiceImpl.java` | `ff421b0baf53` | 271 | full |
| 72 | `product-service/src/main/java/com/shop/productservice/service/impls/ProductVariantServiceImpl.java` | `7fee033770fa` | 153 | full |
| 73 | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalBrandEventPublisher.java` | `9c2f14c0f50b` | 83 | full |
| 74 | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalCategoryEventPublisher.java` | `4669a42b80b0` | 84 | full |
| 75 | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java` | `16269716dbff` | 102 | full |
| 76 | `product-service/src/main/resources/application.yml` | `1444f56c07a0` | 107 | full |
| 77 | `product-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | `011bc05eb311` | 224 | full |
| 78 | `product-service/src/main/resources/db/changelog/changelog-002-outbox-pending-index.yaml` | `71184eb0d8e6` | 20 | full |
| 79 | `product-service/src/main/resources/db/changelog/changelog-003-rating-columns.yaml` | `04e4c0b06f0c` | 16 | full |
| 80 | `product-service/src/main/resources/db/changelog/changelog-004-media-reference.yaml` | `a65eb61fb317` | 16 | full |
| 81 | `product-service/src/main/resources/db/changelog/changelog-005-media-id-index.yaml` | `e8e3f3ad5850` | 19 | full |
| 82 | `product-service/src/main/resources/db/changelog/changelog-006-product-variants.yaml` | `3ef1e780b8e4` | 90 | full |
| 83 | `product-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `520bcae4587f` | 19 | full |
| 84 | `product-service/src/test/java/com/shop/productservice/client/InventoryReservationClientTest.java` | `d32d94c03e9b` | 114 | full |
| 85 | `product-service/src/test/java/com/shop/productservice/client/MediaHeadClientTest.java` | `5c35a15b6240` | 122 | full |
| 86 | `product-service/src/test/java/com/shop/productservice/config/TestLiquibaseConfig.java` | `887c826d0671` | 28 | full |
| 87 | `product-service/src/test/java/com/shop/productservice/controller/BackofficeBrandControllerTest.java` | `8b79f7965f37` | 141 | full |
| 88 | `product-service/src/test/java/com/shop/productservice/controller/BackofficeCategoryControllerTest.java` | `c96d5dff6b10` | 161 | full |
| 89 | `product-service/src/test/java/com/shop/productservice/controller/BackofficeProductControllerTest.java` | `d333a5b6c746` | 310 | full |
| 90 | `product-service/src/test/java/com/shop/productservice/controller/BrandControllerTest.java` | `117434c68a66` | 45 | full |
| 91 | `product-service/src/test/java/com/shop/productservice/controller/InternalProductMediaControllerTest.java` | `ad2fd47d30f5` | 114 | full |
| 92 | `product-service/src/test/java/com/shop/productservice/controller/MediaHeadValidationIT.java` | `870145c4ffc6` | 297 | full |
| 93 | `product-service/src/test/java/com/shop/productservice/controller/ProductControllerAuditTest.java` | `a12f380cd875` | 106 | full |
| 94 | `product-service/src/test/java/com/shop/productservice/controller/ProductControllerTest.java` | `caa7bf247bdf` | 75 | full |
| 95 | `product-service/src/test/java/com/shop/productservice/controller/ProductVariantControllerTest.java` | `953b285b54e6` | 67 | full |
| 96 | `product-service/src/test/java/com/shop/productservice/i18n/ProductI18nKeysTest.java` | `b61fac4ff6c3` | 38 | full |
| 97 | `product-service/src/test/java/com/shop/productservice/job/ProductMediaSweepJobIT.java` | `9ba5922bfa55` | 174 | full |
| 98 | `product-service/src/test/java/com/shop/productservice/job/ProductMediaSweepJobTest.java` | `9b51a9ee3e1d` | 195 | full |
| 99 | `product-service/src/test/java/com/shop/productservice/kafka/MediaDeletedConsumerIT.java` | `c1a649924974` | 235 | full |
| 100 | `product-service/src/test/java/com/shop/productservice/kafka/MediaDeletedConsumerTest.java` | `d0488651b2db` | 208 | full |
| 101 | `product-service/src/test/java/com/shop/productservice/kafka/ProductRatingConsumerTest.java` | `cd9251c70c62` | 98 | full |
| 102 | `product-service/src/test/java/com/shop/productservice/kafka/RatingLifecycleConsumerIT.java` | `d9d054b139a5` | 225 | full |
| 103 | `product-service/src/test/java/com/shop/productservice/mapper/ProductMapperMediaFieldsTest.java` | `bfbf4de37520` | 163 | full |
| 104 | `product-service/src/test/java/com/shop/productservice/mapper/ProductMapperRatingFieldsTest.java` | `ab20fb753ea9` | 53 | full |
| 105 | `product-service/src/test/java/com/shop/productservice/repository/ProductRepositoryTest.java` | `9a5bee3e0f63` | 166 | full |
| 106 | `product-service/src/test/java/com/shop/productservice/service/OutboxRelayIntegrationTest.java` | `e8ad287f44c6` | 93 | full |
| 107 | `product-service/src/test/java/com/shop/productservice/service/OutboxRelayRecoveryIT.java` | `cad2e136f49e` | 198 | full |
| 108 | `product-service/src/test/java/com/shop/productservice/service/OutboxRelayTest.java` | `874512343ab4` | 209 | full |
| 109 | `product-service/src/test/java/com/shop/productservice/service/ProductMediaServiceTest.java` | `96726a6beab5` | 68 | full |
| 110 | `product-service/src/test/java/com/shop/productservice/service/ProductOutboxClaimConcurrencyIT.java` | `999eaea5c6c1` | 186 | full |
| 111 | `product-service/src/test/java/com/shop/productservice/service/ProductRatingOutboxIntegrationTest.java` | `80bb08fda67d` | 75 | full |
| 112 | `product-service/src/test/java/com/shop/productservice/service/ProductRatingServiceTest.java` | `218f01e0de61` | 161 | full |
| 113 | `product-service/src/test/java/com/shop/productservice/service/ProductServiceFilterSpecIT.java` | `3f61903a4225` | 117 | full |
| 114 | `product-service/src/test/java/com/shop/productservice/service/ProductVariantServiceTest.java` | `24db9a525cb8` | 152 | full |
| 115 | `product-service/src/test/java/com/shop/productservice/service/impls/BrandServiceImplTest.java` | `30a5ee807820` | 132 | full |
| 116 | `product-service/src/test/java/com/shop/productservice/service/impls/CategoryServiceImplTest.java` | `b693dcd70d0d` | 173 | full |
| 117 | `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplDeleteGuardTest.java` | `2483d0682f50` | 140 | full |
| 118 | `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplMediaClearTest.java` | `a925aaf7c2e8` | 127 | full |
| 119 | `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplTest.java` | `b4ff5471abc1` | 441 | full |
| 120 | `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplTransactionBoundaryIT.java` | `c01d5a815f83` | 58 | full |
| 121 | `product-service/src/test/java/com/shop/productservice/service/impls/TransactionalProductEventPublisherTest.java` | `d28456e6520d` | 205 | full |
| 122 | `product-service/src/test/java/com/shop/productservice/support/AbstractIntegrationTest.java` | `47d910e79176` | 113 | full |
| 123 | `search-service/src/main/java/com/shop/searchservice/SearchServiceApplication.java` | `4a5026c1359b` | 15 | full |
| 124 | `search-service/src/main/java/com/shop/searchservice/client/ProductBackofficeClient.java` | `3b5b4a7bdd4a` | 94 | full |
| 125 | `search-service/src/main/java/com/shop/searchservice/config/ElasticsearchConfig.java` | `e440f3dc9a3d` | 49 | full |
| 126 | `search-service/src/main/java/com/shop/searchservice/config/ProductClientConfig.java` | `31632bc75859` | 65 | full |
| 127 | `search-service/src/main/java/com/shop/searchservice/config/SearchProperties.java` | `577e5e7afd74` | 14 | full |
| 128 | `search-service/src/main/java/com/shop/searchservice/config/ShopServicesProperties.java` | `75afbf40ada6` | 29 | full |
| 129 | `search-service/src/main/java/com/shop/searchservice/controller/BackofficeSearchController.java` | `8435b959769f` | 36 | full |
| 130 | `search-service/src/main/java/com/shop/searchservice/controller/SearchController.java` | `f979e331ba6a` | 44 | full |
| 131 | `search-service/src/main/java/com/shop/searchservice/dto/request/ReindexRequest.java` | `467a3ec49d7f` | 9 | full |
| 132 | `search-service/src/main/java/com/shop/searchservice/dto/request/SearchRequest.java` | `1e13876e53ea` | 56 | full |
| 133 | `search-service/src/main/java/com/shop/searchservice/dto/response/ProductSearchResponse.java` | `d4e2dfe96999` | 21 | full |
| 134 | `search-service/src/main/java/com/shop/searchservice/dto/response/ReindexResponse.java` | `b5afd6e02d45` | 12 | full |
| 135 | `search-service/src/main/java/com/shop/searchservice/kafka/ProductLifecycleEvent.java` | `6eb31da1f9a7` | 32 | full |
| 136 | `search-service/src/main/java/com/shop/searchservice/kafka/ProductSearchConsumer.java` | `a7ecefd1870e` | 71 | full |
| 137 | `search-service/src/main/java/com/shop/searchservice/kafka/SearchListenerConfig.java` | `89d2786d812a` | 31 | full |
| 138 | `search-service/src/main/java/com/shop/searchservice/metrics/SearchMetrics.java` | `81a3351cbebe` | 28 | full |
| 139 | `search-service/src/main/java/com/shop/searchservice/search/IndexProvisioner.java` | `4af9f77ab393` | 79 | full |
| 140 | `search-service/src/main/java/com/shop/searchservice/security/ServiceTokenProvider.java` | `254d3fcce4e9` | 97 | full |
| 141 | `search-service/src/main/java/com/shop/searchservice/service/ProductDocuments.java` | `fbcb75ad548b` | 74 | full |
| 142 | `search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java` | `daedf01c9f65` | 72 | full |
| 143 | `search-service/src/main/java/com/shop/searchservice/service/ReindexService.java` | `f8e080055ddc` | 21 | full |
| 144 | `search-service/src/main/java/com/shop/searchservice/service/SearchQueryService.java` | `e1bda5a2a7b5` | 22 | full |
| 145 | `search-service/src/main/java/com/shop/searchservice/service/impls/ReindexServiceImpl.java` | `d5b6a466cfc2` | 219 | full |
| 146 | `search-service/src/main/java/com/shop/searchservice/service/impls/SearchQueryServiceImpl.java` | `7137e00d3b0c` | 326 | full |
| 147 | `search-service/src/main/resources/application.yml` | `80dae59ba78e` | 36 | full |
| 148 | `search-service/src/test/java/com/shop/searchservice/client/ProductBackofficeClientTest.java` | `3d5f2f0c49ba` | 174 | full |
| 149 | `search-service/src/test/java/com/shop/searchservice/controller/BackofficeSearchAuditTest.java` | `ddee842c970f` | 75 | full |
| 150 | `search-service/src/test/java/com/shop/searchservice/controller/BackofficeSearchControllerTest.java` | `23004080febe` | 149 | full |
| 151 | `search-service/src/test/java/com/shop/searchservice/controller/SearchControllerTest.java` | `5ba9875611d9` | 207 | full |
| 152 | `search-service/src/test/java/com/shop/searchservice/kafka/ProductSearchIngestionIT.java` | `80fd357fc77f` | 376 | full |
| 153 | `search-service/src/test/java/com/shop/searchservice/search/SearchIndexProvisioningIT.java` | `17d145a85dad` | 39 | full |
| 154 | `search-service/src/test/java/com/shop/searchservice/service/impls/ReindexIT.java` | `94d207babd7e` | 253 | full |
| 155 | `search-service/src/test/java/com/shop/searchservice/service/impls/ReindexServiceTest.java` | `8e2a7e8cfac1` | 57 | full |
| 156 | `search-service/src/test/java/com/shop/searchservice/service/impls/SearchQueryIT.java` | `3f4c00d80cfe` | 314 | full |
| 157 | `search-service/src/test/java/com/shop/searchservice/service/impls/SearchQueryServiceTest.java` | `4398748e6236` | 214 | full |
| 158 | `search-service/src/test/java/com/shop/searchservice/support/AbstractSearchIntegrationTest.java` | `18715cf2a645` | 43 | full |
| 159 | `media-service/src/main/java/com/shop/mediaservice/MediaServiceApplication.java` | `6ef4fcf6df83` | 17 | full |
| 160 | `media-service/src/main/java/com/shop/mediaservice/client/MediaReferenceClient.java` | `c89737421688` | 79 | full |
| 161 | `media-service/src/main/java/com/shop/mediaservice/config/MediaProperties.java` | `d52a0f38b53f` | 48 | full |
| 162 | `media-service/src/main/java/com/shop/mediaservice/config/ProductClientConfig.java` | `e9987039a3b9` | 82 | full |
| 163 | `media-service/src/main/java/com/shop/mediaservice/config/ProductClientProperties.java` | `c155aafbbe9b` | 27 | full |
| 164 | `media-service/src/main/java/com/shop/mediaservice/config/StorageConfig.java` | `24816e979d31` | 41 | full |
| 165 | `media-service/src/main/java/com/shop/mediaservice/config/UploadLimitsConfig.java` | `7da5a3c41ee6` | 32 | full |
| 166 | `media-service/src/main/java/com/shop/mediaservice/controller/BackofficeMediaController.java` | `9af9738b3985` | 47 | full |
| 167 | `media-service/src/main/java/com/shop/mediaservice/controller/MediaPublicController.java` | `17d5e591b751` | 47 | full |
| 168 | `media-service/src/main/java/com/shop/mediaservice/dto/response/MediaResponse.java` | `e987634b4d11` | 14 | full |
| 169 | `media-service/src/main/java/com/shop/mediaservice/dto/response/MediaVariantResponse.java` | `240e2a89375b` | 13 | full |
| 170 | `media-service/src/main/java/com/shop/mediaservice/entity/Media.java` | `17d34853a883` | 74 | full |
| 171 | `media-service/src/main/java/com/shop/mediaservice/entity/MediaVariant.java` | `5f478c1d6e40` | 56 | full |
| 172 | `media-service/src/main/java/com/shop/mediaservice/job/MediaPurgeJob.java` | `4c5f90eec2fd` | 63 | full |
| 173 | `media-service/src/main/java/com/shop/mediaservice/metrics/MediaMetrics.java` | `c1daddc1f9a2` | 43 | full |
| 174 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaEventService.java` | `7e120bdcfba7` | 83 | full |
| 175 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaEventType.java` | `be7d6360ff3f` | 11 | full |
| 176 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaOutboxRelay.java` | `bb2505c72f79` | 256 | full |
| 177 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaOutboxRetentionScheduler.java` | `9088d674e5ff` | 47 | full |
| 178 | `media-service/src/main/java/com/shop/mediaservice/outbox/OutboxEvent.java` | `232d89059348` | 61 | full |
| 179 | `media-service/src/main/java/com/shop/mediaservice/outbox/OutboxEventRepository.java` | `30297ca6f6d0` | 105 | full |
| 180 | `media-service/src/main/java/com/shop/mediaservice/repository/MediaRepository.java` | `dbb4b8e00406` | 70 | full |
| 181 | `media-service/src/main/java/com/shop/mediaservice/security/ServiceTokenProvider.java` | `23af8fce5a1d` | 125 | full |
| 182 | `media-service/src/main/java/com/shop/mediaservice/service/MediaLifecycleService.java` | `ddabc72ea8b1` | 23 | full |
| 183 | `media-service/src/main/java/com/shop/mediaservice/service/MediaQueryService.java` | `51aa4c1f6ced` | 28 | full |
| 184 | `media-service/src/main/java/com/shop/mediaservice/service/MediaReferenceChecker.java` | `26b010c03389` | 22 | full |
| 185 | `media-service/src/main/java/com/shop/mediaservice/service/MediaUploadService.java` | `a89430b2b68b` | 9 | full |
| 186 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaFormats.java` | `06d6c4235854` | 70 | full |
| 187 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaLifecycleServiceImpl.java` | `cb9c137eddaa` | 85 | full |
| 188 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaMetadataInspector.java` | `4af2db011e3f` | 48 | full |
| 189 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaQueryServiceImpl.java` | `f54d77fcefd9` | 94 | full |
| 190 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaUploadServiceImpl.java` | `93a37b17bcb2` | 248 | full |
| 191 | `media-service/src/main/java/com/shop/mediaservice/service/impls/ProductMediaReferenceChecker.java` | `a5ae1af811d4` | 36 | full |
| 192 | `media-service/src/main/java/com/shop/mediaservice/service/impls/VariantRenderer.java` | `985201a05a1d` | 177 | full |
| 193 | `media-service/src/main/java/com/shop/mediaservice/storage/BucketBootstrap.java` | `635b02d9e0bd` | 78 | full |
| 194 | `media-service/src/main/resources/application.yml` | `2bbd319a5bb0` | 81 | full |
| 195 | `media-service/src/main/resources/db/changelog/changelog-001-medias.yaml` | `894ae17113f3` | 66 | full |
| 196 | `media-service/src/main/resources/db/changelog/changelog-002-outbox.yaml` | `43eb3e30fcb5` | 14 | full |
| 197 | `media-service/src/main/resources/db/changelog/changelog-003-outbox-aging.yaml` | `177033dfa5df` | 18 | full |
| 198 | `media-service/src/main/resources/db/changelog/changelog-004-media-outbox-claim-lease.yaml` | `f2f52d077379` | 14 | full |
| 199 | `media-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `0d8022f16180` | 13 | full |
| 200 | `media-service/src/test/java/com/shop/mediaservice/client/MediaReferenceClientIT.java` | `c9302a7519f9` | 128 | full |
| 201 | `media-service/src/test/java/com/shop/mediaservice/controller/BackofficeMediaControllerAuditTest.java` | `92e40a92a8d3` | 106 | full |
| 202 | `media-service/src/test/java/com/shop/mediaservice/controller/BackofficeMediaControllerTest.java` | `083e3f23927c` | 181 | full |
| 203 | `media-service/src/test/java/com/shop/mediaservice/controller/MediaControllerIT.java` | `233676dececd` | 340 | full |
| 204 | `media-service/src/test/java/com/shop/mediaservice/controller/MediaPublicControllerTest.java` | `f305194a8da3` | 175 | full |
| 205 | `media-service/src/test/java/com/shop/mediaservice/controller/MediaUploadMemoryBudgetIT.java` | `3d3945d953c3` | 112 | full |
| 206 | `media-service/src/test/java/com/shop/mediaservice/i18n/MediaErrorCodeI18nCoverageTest.java` | `8be157bcd313` | 88 | full |
| 207 | `media-service/src/test/java/com/shop/mediaservice/i18n/MediaI18nKeysTest.java` | `b55b176c15e2` | 48 | full |
| 208 | `media-service/src/test/java/com/shop/mediaservice/job/MediaPurgeIT.java` | `dce99b480b64` | 259 | full |
| 209 | `media-service/src/test/java/com/shop/mediaservice/job/MediaPurgeJobTest.java` | `84b7ac39b6cb` | 204 | full |
| 210 | `media-service/src/test/java/com/shop/mediaservice/metrics/MediaMetricsTest.java` | `ba927184ec3e` | 49 | full |
| 211 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaEventServiceTest.java` | `db42fcc29a10` | 140 | full |
| 212 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxClaimConcurrencyIT.java` | `34048ebd94d2` | 186 | full |
| 213 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxIT.java` | `3ff34a02da1a` | 415 | full |
| 214 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxRelayRecoveryIT.java` | `4f6c64ded97e` | 192 | full |
| 215 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxRelayTest.java` | `8b12e0649dc9` | 270 | full |
| 216 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxRetentionIT.java` | `304c41f3d227` | 126 | full |
| 217 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxRetentionSchedulerTest.java` | `d8902ed81634` | 93 | full |
| 218 | `media-service/src/test/java/com/shop/mediaservice/service/impls/MediaBucketUnificationTest.java` | `370c7fd4ec88` | 171 | full |
| 219 | `media-service/src/test/java/com/shop/mediaservice/service/impls/MediaFormatsTest.java` | `186abb642e90` | 58 | full |
| 220 | `media-service/src/test/java/com/shop/mediaservice/service/impls/MediaLifecycleServiceImplTest.java` | `6e6dbf735064` | 139 | full |
| 221 | `media-service/src/test/java/com/shop/mediaservice/service/impls/MediaQueryServiceImplTest.java` | `e47dce47978a` | 239 | full |
| 222 | `media-service/src/test/java/com/shop/mediaservice/service/impls/MediaUploadServiceImplTest.java` | `45ee93156c55` | 130 | full |
| 223 | `media-service/src/test/java/com/shop/mediaservice/service/impls/UploadIT.java` | `c6681ffd94a1` | 519 | full |
| 224 | `media-service/src/test/java/com/shop/mediaservice/service/impls/VariantRendererMemoryBudgetTest.java` | `c593b10f694c` | 73 | full |
| 225 | `media-service/src/test/java/com/shop/mediaservice/service/impls/VariantRendererObjectCountTest.java` | `f2efdae549bf` | 74 | full |
| 226 | `media-service/src/test/java/com/shop/mediaservice/storage/BucketBootstrapTest.java` | `6c0181ce7faf` | 114 | full |
| 227 | `media-service/src/test/java/com/shop/mediaservice/storage/ProvisioningIT.java` | `968af8ad2c85` | 77 | full |
| 228 | `media-service/src/test/java/com/shop/mediaservice/support/AbstractMediaIntegrationTest.java` | `f9643cd10a64` | 80 | full |
| 229 | `media-service/src/test/java/com/shop/mediaservice/support/TestImages.java` | `8588c61fbdfc` | 144 | full |
| 230 | `promotion-service/src/main/java/com/shop/promotionservice/PromotionServiceApplication.java` | `5de3b97aaf35` | 15 | full |
| 231 | `promotion-service/src/main/java/com/shop/promotionservice/config/ClockConfig.java` | `98d02bb6f633` | 20 | full |
| 232 | `promotion-service/src/main/java/com/shop/promotionservice/constant/CampaignStatus.java` | `92a32675b573` | 9 | full |
| 233 | `promotion-service/src/main/java/com/shop/promotionservice/constant/UsageStatus.java` | `8743cd9a74ef` | 10 | full |
| 234 | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` | `3220dde2fb67` | 85 | full |
| 235 | `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` | `a87354249990` | 75 | full |
| 236 | `promotion-service/src/main/java/com/shop/promotionservice/dto/request/CampaignRequest.java` | `75dc0208588a` | 29 | full |
| 237 | `promotion-service/src/main/java/com/shop/promotionservice/dto/request/ReserveRequest.java` | `f29fa158c2c0` | 13 | full |
| 238 | `promotion-service/src/main/java/com/shop/promotionservice/dto/response/CampaignResponse.java` | `481cc2d3895f` | 45 | full |
| 239 | `promotion-service/src/main/java/com/shop/promotionservice/dto/response/CampaignUsageResponse.java` | `b5025c930ffe` | 35 | full |
| 240 | `promotion-service/src/main/java/com/shop/promotionservice/dto/response/ReservationResponse.java` | `77ae0777ebac` | 31 | full |
| 241 | `promotion-service/src/main/java/com/shop/promotionservice/entity/Campaign.java` | `c0c313cff1fa` | 75 | full |
| 242 | `promotion-service/src/main/java/com/shop/promotionservice/entity/CouponUsageReservation.java` | `286da57a05d0` | 77 | full |
| 243 | `promotion-service/src/main/java/com/shop/promotionservice/entity/OutboxEvent.java` | `0d93391b50f4` | 83 | full |
| 244 | `promotion-service/src/main/java/com/shop/promotionservice/repository/CampaignRepository.java` | `3c24f2f26c47` | 28 | full |
| 245 | `promotion-service/src/main/java/com/shop/promotionservice/repository/CouponUsageReservationRepository.java` | `2a8a26e2aa1c` | 75 | full |
| 246 | `promotion-service/src/main/java/com/shop/promotionservice/repository/OutboxEventRepository.java` | `044448a5f603` | 93 | full |
| 247 | `promotion-service/src/main/java/com/shop/promotionservice/service/CampaignReservationService.java` | `2fcbb8ff01f4` | 28 | full |
| 248 | `promotion-service/src/main/java/com/shop/promotionservice/service/CampaignService.java` | `e2bed3057dac` | 25 | full |
| 249 | `promotion-service/src/main/java/com/shop/promotionservice/service/DiscountCalculator.java` | `564f9de48d8c` | 34 | full |
| 250 | `promotion-service/src/main/java/com/shop/promotionservice/service/OutboxRetentionScheduler.java` | `d47647db7160` | 39 | full |
| 251 | `promotion-service/src/main/java/com/shop/promotionservice/service/PromotionEventPublisher.java` | `071dcaae9719` | 19 | full |
| 252 | `promotion-service/src/main/java/com/shop/promotionservice/service/PromotionMetrics.java` | `f20accd43e8c` | 49 | full |
| 253 | `promotion-service/src/main/java/com/shop/promotionservice/service/PromotionOutboxRelay.java` | `fef736bfea52` | 257 | full |
| 254 | `promotion-service/src/main/java/com/shop/promotionservice/service/ReservationCleanupScheduler.java` | `d06b9ca60a20` | 146 | full |
| 255 | `promotion-service/src/main/java/com/shop/promotionservice/service/ReservationRetryService.java` | `36fcfc649e53` | 24 | full |
| 256 | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java` | `3da6beb76354` | 234 | full |
| 257 | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignServiceImpl.java` | `1b9053dd92d4` | 148 | full |
| 258 | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/ReservationRetryServiceImpl.java` | `0c063ba74328` | 103 | full |
| 259 | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/TransactionalPromotionEventPublisher.java` | `376323f7283b` | 111 | full |
| 260 | `promotion-service/src/main/java/com/shop/promotionservice/validation/ValidDiscountValue.java` | `a249991171af` | 29 | full |
| 261 | `promotion-service/src/main/java/com/shop/promotionservice/validation/ValidDiscountValueValidator.java` | `064ae8240efe` | 32 | full |
| 262 | `promotion-service/src/main/resources/application.yml` | `f325a06c6a5a` | 70 | full |
| 263 | `promotion-service/src/main/resources/db/changelog/005-promotion-outbox-claim-lease.yaml` | `a7cbe136bc49` | 21 | full |
| 264 | `promotion-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | `d899029a1bd8` | 123 | full |
| 265 | `promotion-service/src/main/resources/db/changelog/changelog-002-outbox.yaml` | `dce0742c67bc` | 45 | full |
| 266 | `promotion-service/src/main/resources/db/changelog/changelog-003-reservation-query-indexes.yaml` | `e185b1cdb348` | 13 | full |
| 267 | `promotion-service/src/main/resources/db/changelog/changelog-004-coupon-reservation-version.yaml` | `58b4476dc324` | 35 | full |
| 268 | `promotion-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `4244192b4e75` | 16 | full |
| 269 | `promotion-service/src/test/java/com/shop/promotionservice/config/TestLiquibaseConfig.java` | `59d5c10432bf` | 22 | full |
| 270 | `promotion-service/src/test/java/com/shop/promotionservice/controller/BackofficeCampaignAuditTest.java` | `37a5e16b0894` | 90 | full |
| 271 | `promotion-service/src/test/java/com/shop/promotionservice/controller/BackofficeCampaignControllerTest.java` | `14289b1ca20f` | 340 | full |
| 272 | `promotion-service/src/test/java/com/shop/promotionservice/controller/PromotionReservationAuditTest.java` | `1138fbb62428` | 115 | full |
| 273 | `promotion-service/src/test/java/com/shop/promotionservice/controller/PromotionReservationControllerTest.java` | `d199e7508133` | 247 | full |
| 274 | `promotion-service/src/test/java/com/shop/promotionservice/dto/request/CampaignRequestValidationTest.java` | `ace423a52165` | 114 | full |
| 275 | `promotion-service/src/test/java/com/shop/promotionservice/service/CampaignLifecycleTest.java` | `8230e71dea0e` | 322 | full |
| 276 | `promotion-service/src/test/java/com/shop/promotionservice/service/CampaignReserveTest.java` | `1b2b637bf4f4` | 349 | full |
| 277 | `promotion-service/src/test/java/com/shop/promotionservice/service/CampaignServiceTest.java` | `354ce2818729` | 432 | full |
| 278 | `promotion-service/src/test/java/com/shop/promotionservice/service/DiscountCalculatorTest.java` | `6190dc2db03c` | 64 | full |
| 279 | `promotion-service/src/test/java/com/shop/promotionservice/service/LifecycleAndEventsIT.java` | `56f4911ff4c8` | 317 | full |
| 280 | `promotion-service/src/test/java/com/shop/promotionservice/service/PromotionOutboxClaimConcurrencyIT.java` | `15280a9502ff` | 186 | full |
| 281 | `promotion-service/src/test/java/com/shop/promotionservice/service/PromotionOutboxRelayRecoveryIT.java` | `3261056870ff` | 193 | full |
| 282 | `promotion-service/src/test/java/com/shop/promotionservice/service/PromotionOutboxRelayTest.java` | `e16d6cd67151` | 209 | full |
| 283 | `promotion-service/src/test/java/com/shop/promotionservice/service/ReservationCleanupSchedulerTest.java` | `8be6e962e496` | 239 | full |
| 284 | `promotion-service/src/test/java/com/shop/promotionservice/service/ReserveFlowIT.java` | `8ef4d4d42894` | 374 | full |
| 285 | `promotion-service/src/test/java/com/shop/promotionservice/service/impls/ReservationRetryServiceImplTest.java` | `27b9bc7bbde7` | 151 | full |
| 286 | `promotion-service/src/test/java/com/shop/promotionservice/support/AbstractIntegrationTest.java` | `6011a685edda` | 112 | full |
| 287 | `promotion-service/src/test/java/com/shop/promotionservice/support/PromotionBootstrapIT.java` | `9d4e6fc487f8` | 57 | full |
| 288 | `tax-service/src/main/java/com/shop/taxservice/TaxServiceApplication.java` | `82672f4329ef` | 13 | full |
| 289 | `tax-service/src/main/java/com/shop/taxservice/constant/TaxConstants.java` | `6df7c319179e` | 9 | full |
| 290 | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxClassController.java` | `cec9c3e6b400` | 61 | full |
| 291 | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxRateController.java` | `7a778625a4e2` | 62 | full |
| 292 | `tax-service/src/main/java/com/shop/taxservice/controller/TaxCalculationController.java` | `723057b614cf` | 33 | full |
| 293 | `tax-service/src/main/java/com/shop/taxservice/dto/request/TaxCalculateRequest.java` | `5e1dca2707b5` | 15 | full |
| 294 | `tax-service/src/main/java/com/shop/taxservice/dto/request/TaxClassRequest.java` | `57682340e2d0` | 13 | full |
| 295 | `tax-service/src/main/java/com/shop/taxservice/dto/request/TaxRateRequest.java` | `ea3ffb444f38` | 16 | full |
| 296 | `tax-service/src/main/java/com/shop/taxservice/dto/response/TaxCalculateResponse.java` | `e86422bcdf11` | 5 | full |
| 297 | `tax-service/src/main/java/com/shop/taxservice/dto/response/TaxClassResponse.java` | `306c857dabad` | 6 | full |
| 298 | `tax-service/src/main/java/com/shop/taxservice/dto/response/TaxRateResponse.java` | `6a07efcd72c4` | 6 | full |
| 299 | `tax-service/src/main/java/com/shop/taxservice/entity/TaxClass.java` | `dc5b43199834` | 34 | full |
| 300 | `tax-service/src/main/java/com/shop/taxservice/entity/TaxRate.java` | `9de640b489c7` | 43 | full |
| 301 | `tax-service/src/main/java/com/shop/taxservice/repository/TaxClassRepository.java` | `51e73a9e665a` | 14 | full |
| 302 | `tax-service/src/main/java/com/shop/taxservice/repository/TaxRateRepository.java` | `884b110ce3c4` | 42 | full |
| 303 | `tax-service/src/main/java/com/shop/taxservice/service/TaxCalculationService.java` | `10a2535fdb6c` | 9 | full |
| 304 | `tax-service/src/main/java/com/shop/taxservice/service/TaxCalculator.java` | `ed244675cc48` | 17 | full |
| 305 | `tax-service/src/main/java/com/shop/taxservice/service/TaxClassService.java` | `2ba8f70c8b7e` | 20 | full |
| 306 | `tax-service/src/main/java/com/shop/taxservice/service/TaxRateService.java` | `2d07bcc42946` | 20 | full |
| 307 | `tax-service/src/main/java/com/shop/taxservice/service/impls/TaxCalculationServiceImpl.java` | `654310b7fae9` | 52 | full |
| 308 | `tax-service/src/main/java/com/shop/taxservice/service/impls/TaxClassServiceImpl.java` | `a9d6309add0d` | 86 | full |
| 309 | `tax-service/src/main/java/com/shop/taxservice/service/impls/TaxRateServiceImpl.java` | `2baa8d6bef05` | 114 | full |
| 310 | `tax-service/src/main/resources/application.yml` | `95900925ee17` | 26 | full |
| 311 | `tax-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | `41ec97e2da3d` | 73 | full |
| 312 | `tax-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `4a6da29d0057` | 4 | full |
| 313 | `tax-service/src/test/java/com/shop/taxservice/config/TestLiquibaseConfig.java` | `7a45aea409d6` | 22 | full |
| 314 | `tax-service/src/test/java/com/shop/taxservice/controller/BackofficeTaxAuditTest.java` | `2b7420779809` | 144 | full |
| 315 | `tax-service/src/test/java/com/shop/taxservice/controller/BackofficeTaxClassControllerTest.java` | `e165ab29429f` | 210 | full |
| 316 | `tax-service/src/test/java/com/shop/taxservice/controller/BackofficeTaxRateControllerTest.java` | `f5f4e893e8fb` | 217 | full |
| 317 | `tax-service/src/test/java/com/shop/taxservice/controller/TaxCalculationControllerTest.java` | `f7ee4f076d69` | 168 | full |
| 318 | `tax-service/src/test/java/com/shop/taxservice/service/TaxCalculationIT.java` | `ae5c072b5695` | 127 | full |
| 319 | `tax-service/src/test/java/com/shop/taxservice/service/TaxCalculatorTest.java` | `e4443805c6cd` | 47 | full |
| 320 | `tax-service/src/test/java/com/shop/taxservice/service/TaxCatalogIT.java` | `3ed52bd5c6f2` | 101 | full |
| 321 | `tax-service/src/test/java/com/shop/taxservice/service/impls/TaxCalculationServiceImplTest.java` | `a90f46366620` | 135 | full |
| 322 | `tax-service/src/test/java/com/shop/taxservice/service/impls/TaxClassServiceImplTest.java` | `928485c19baf` | 210 | full |
| 323 | `tax-service/src/test/java/com/shop/taxservice/service/impls/TaxRateServiceImplTest.java` | `324f3a7d27c6` | 224 | full |
| 324 | `tax-service/src/test/java/com/shop/taxservice/support/AbstractIntegrationTest.java` | `b33c4d3c7128` | 88 | full |
| 325 | `tax-service/src/test/java/com/shop/taxservice/support/TaxBootstrapIT.java` | `90dc7df6ed22` | 47 | full |
| 326 | `rating-service/src/main/java/com/shop/ratingservice/RatingServiceApplication.java` | `81f2d4c8f615` | 17 | full |
| 327 | `rating-service/src/main/java/com/shop/ratingservice/config/RatingClientProperties.java` | `239723c922cd` | 31 | full |
| 328 | `rating-service/src/main/java/com/shop/ratingservice/config/RestClientConfig.java` | `8c4c47a7ef4a` | 87 | full |
| 329 | `rating-service/src/main/java/com/shop/ratingservice/constant/RatingAction.java` | `da08c3f4b86a` | 6 | full |
| 330 | `rating-service/src/main/java/com/shop/ratingservice/controller/BackofficeRatingController.java` | `e65a73d6709e` | 43 | full |
| 331 | `rating-service/src/main/java/com/shop/ratingservice/controller/StorefrontRatingController.java` | `ce9a69037fbe` | 68 | full |
| 332 | `rating-service/src/main/java/com/shop/ratingservice/dto/request/RatingEditRequest.java` | `0fc287f00b4c` | 18 | full |
| 333 | `rating-service/src/main/java/com/shop/ratingservice/dto/request/RatingHideRequest.java` | `c95d157d539a` | 11 | full |
| 334 | `rating-service/src/main/java/com/shop/ratingservice/dto/request/RatingSubmitRequest.java` | `c8114082c892` | 23 | full |
| 335 | `rating-service/src/main/java/com/shop/ratingservice/dto/response/RatingResponse.java` | `2269e66337a7` | 32 | full |
| 336 | `rating-service/src/main/java/com/shop/ratingservice/eligibility/EligibilityClient.java` | `4487c9ce254a` | 73 | full |
| 337 | `rating-service/src/main/java/com/shop/ratingservice/entity/Rating.java` | `fbb20c70e30c` | 64 | full |
| 338 | `rating-service/src/main/java/com/shop/ratingservice/metrics/RatingMetrics.java` | `f800eb62ea64` | 26 | full |
| 339 | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxEvent.java` | `2f04a92e63ca` | 58 | full |
| 340 | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxEventRepository.java` | `048f659e161e` | 86 | full |
| 341 | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxRetentionScheduler.java` | `3cf4f115f4ad` | 43 | full |
| 342 | `rating-service/src/main/java/com/shop/ratingservice/outbox/RatingOutboxRelay.java` | `fbfd993aedf9` | 240 | full |
| 343 | `rating-service/src/main/java/com/shop/ratingservice/repository/RatingRepository.java` | `eed623a69283` | 25 | full |
| 344 | `rating-service/src/main/java/com/shop/ratingservice/security/ServiceTokenProvider.java` | `68bb6c1f196c` | 123 | full |
| 345 | `rating-service/src/main/java/com/shop/ratingservice/service/RatingEventService.java` | `f989fbd38eea` | 106 | full |
| 346 | `rating-service/src/main/java/com/shop/ratingservice/service/RatingService.java` | `fd26880a2f97` | 21 | full |
| 347 | `rating-service/src/main/java/com/shop/ratingservice/service/impls/RatingServiceImpl.java` | `ea96f02ad962` | 138 | full |
| 348 | `rating-service/src/main/resources/application.yml` | `27f31c270ac5` | 43 | full |
| 349 | `rating-service/src/main/resources/db/changelog/changelog-001-ratings.yaml` | `a126b1c1329e` | 56 | full |
| 350 | `rating-service/src/main/resources/db/changelog/changelog-002-rating-outbox-claim-lease.yaml` | `e41a8d48b9d1` | 18 | full |
| 351 | `rating-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `22694bb0a7aa` | 7 | full |
| 352 | `rating-service/src/test/java/com/shop/ratingservice/config/TestLiquibaseConfig.java` | `e61d6ba50bd8` | 22 | full |
| 353 | `rating-service/src/test/java/com/shop/ratingservice/controller/BackofficeRatingAuditTest.java` | `967a9a884daa` | 95 | full |
| 354 | `rating-service/src/test/java/com/shop/ratingservice/controller/BackofficeRatingControllerTest.java` | `37897615d1eb` | 221 | full |
| 355 | `rating-service/src/test/java/com/shop/ratingservice/controller/StorefrontRatingControllerTest.java` | `97c03a7601b2` | 185 | full |
| 356 | `rating-service/src/test/java/com/shop/ratingservice/dto/RatingSubmitValidationTest.java` | `7c5e42f562de` | 78 | full |
| 357 | `rating-service/src/test/java/com/shop/ratingservice/eligibility/EligibilityClientTest.java` | `a4a67b1c8635` | 135 | full |
| 358 | `rating-service/src/test/java/com/shop/ratingservice/entity/RatingMappingIT.java` | `accaaa849b02` | 136 | full |
| 359 | `rating-service/src/test/java/com/shop/ratingservice/i18n/RatingI18nKeysTest.java` | `130aa777d9eb` | 46 | full |
| 360 | `rating-service/src/test/java/com/shop/ratingservice/metrics/RatingMetricsTest.java` | `fe4a0842ae12` | 30 | full |
| 361 | `rating-service/src/test/java/com/shop/ratingservice/outbox/RatingOutboxClaimConcurrencyIT.java` | `c8c9d505d317` | 184 | full |
| 362 | `rating-service/src/test/java/com/shop/ratingservice/outbox/RatingOutboxRelayRecoveryIT.java` | `bdeba6c2fd5e` | 190 | full |
| 363 | `rating-service/src/test/java/com/shop/ratingservice/outbox/RatingOutboxRelayTest.java` | `2a1338173ee1` | 205 | full |
| 364 | `rating-service/src/test/java/com/shop/ratingservice/service/RatingEventServiceTest.java` | `ed0ba8707b04` | 172 | full |
| 365 | `rating-service/src/test/java/com/shop/ratingservice/service/impls/RatingServiceImplEditTest.java` | `c1b0ff51e84b` | 167 | full |
| 366 | `rating-service/src/test/java/com/shop/ratingservice/service/impls/RatingServiceImplModerationTest.java` | `fc2ac3894469` | 183 | full |
| 367 | `rating-service/src/test/java/com/shop/ratingservice/service/impls/RatingServiceImplTest.java` | `eec03fec985e` | 141 | full |
| 368 | `rating-service/src/test/java/com/shop/ratingservice/service/impls/RatingServiceImplTransactionBoundaryIT.java` | `7bd723e35692` | 59 | full |
| 369 | `rating-service/src/test/java/com/shop/ratingservice/support/AbstractIntegrationTest.java` | `18a37cb26fea` | 50 | full |
| 370 | `favourite-service/src/main/java/com/shop/favouriteservice/FavouriteServiceApplication.java` | `f8824c2d2037` | 13 | full |
| 371 | `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` | `9384a4efd424` | 74 | full |
| 372 | `favourite-service/src/main/java/com/shop/favouriteservice/dto/request/FavouriteCreateRequest.java` | `653f43d33fa5` | 10 | full |
| 373 | `favourite-service/src/main/java/com/shop/favouriteservice/dto/response/FavouriteResponse.java` | `540e0665e881` | 11 | full |
| 374 | `favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java` | `5cfd7b5ae97b` | 39 | full |
| 375 | `favourite-service/src/main/java/com/shop/favouriteservice/mapper/FavouriteMapper.java` | `9df8e9006c44` | 19 | full |
| 376 | `favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java` | `658b3bafc729` | 69 | full |
| 377 | `favourite-service/src/main/java/com/shop/favouriteservice/service/FavouriteService.java` | `bb08e163c857` | 21 | full |
| 378 | `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java` | `9edc66cebddd` | 91 | full |
| 379 | `favourite-service/src/main/resources/application.yml` | `42c2da0b4e5d` | 38 | full |
| 380 | `favourite-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | `efbf33e74bd9` | 46 | full |
| 381 | `favourite-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `4a6da29d0057` | 4 | full |
| 382 | `favourite-service/src/test/java/com/shop/favouriteservice/config/TestLiquibaseConfig.java` | `972a145bb730` | 29 | full |
| 383 | `favourite-service/src/test/java/com/shop/favouriteservice/controller/FavouriteControllerTest.java` | `f604cdac0a94` | 159 | full |
| 384 | `favourite-service/src/test/java/com/shop/favouriteservice/repository/FavouriteRepositoryTest.java` | `e3e48aa75876` | 122 | full |
| 385 | `favourite-service/src/test/java/com/shop/favouriteservice/service/impls/FavouriteServiceImplTest.java` | `06a2fd91d00e` | 181 | full |
| 386 | `notification-service/src/main/java/com/shop/notificationservice/NotificationServiceApplication.java` | `b15591a86202` | 23 | full |
| 387 | `notification-service/src/main/java/com/shop/notificationservice/constant/NotificationChannel.java` | `e0a39ea3f26d` | 5 | full |
| 388 | `notification-service/src/main/java/com/shop/notificationservice/constant/NotificationStatus.java` | `064148964361` | 29 | full |
| 389 | `notification-service/src/main/java/com/shop/notificationservice/controller/BackofficeNotificationController.java` | `90f45b330dfe` | 45 | full |
| 390 | `notification-service/src/main/java/com/shop/notificationservice/dto/OrderLifecycleEvent.java` | `c24af105d23b` | 39 | full |
| 391 | `notification-service/src/main/java/com/shop/notificationservice/dto/response/NotificationResponse.java` | `dd6a59766adf` | 34 | full |
| 392 | `notification-service/src/main/java/com/shop/notificationservice/entity/Notification.java` | `92b7d28cc8d6` | 84 | full |
| 393 | `notification-service/src/main/java/com/shop/notificationservice/kafka/NotificationListenerConfig.java` | `16ad2f0a5df4` | 27 | full |
| 394 | `notification-service/src/main/java/com/shop/notificationservice/kafka/OrderEventConsumer.java` | `93dcd08a3f75` | 28 | full |
| 395 | `notification-service/src/main/java/com/shop/notificationservice/repository/NotificationRepository.java` | `895914b9ee83` | 49 | full |
| 396 | `notification-service/src/main/java/com/shop/notificationservice/scheduler/NotificationRetryScheduler.java` | `3f916542be28` | 112 | full |
| 397 | `notification-service/src/main/java/com/shop/notificationservice/service/NotificationDeliveryService.java` | `ac1ae91970af` | 65 | full |
| 398 | `notification-service/src/main/java/com/shop/notificationservice/service/NotificationRetryPolicy.java` | `90f330b0028b` | 52 | full |
| 399 | `notification-service/src/main/java/com/shop/notificationservice/service/NotificationService.java` | `17bd97622547` | 17 | full |
| 400 | `notification-service/src/main/java/com/shop/notificationservice/service/NotificationTemplates.java` | `eb7c80389489` | 54 | full |
| 401 | `notification-service/src/main/java/com/shop/notificationservice/service/NotificationWriter.java` | `e906cad7cc66` | 112 | full |
| 402 | `notification-service/src/main/java/com/shop/notificationservice/service/impls/NotificationServiceImpl.java` | `76474b746c37` | 145 | full |
| 403 | `notification-service/src/main/java/com/shop/notificationservice/service/sender/LoggingNotificationSender.java` | `84c060e0f147` | 21 | full |
| 404 | `notification-service/src/main/java/com/shop/notificationservice/service/sender/NotificationFailureClassifier.java` | `1fc518ded69b` | 38 | full |
| 405 | `notification-service/src/main/java/com/shop/notificationservice/service/sender/NotificationFailureKind.java` | `3f7dd73281dc` | 12 | full |
| 406 | `notification-service/src/main/java/com/shop/notificationservice/service/sender/NotificationSender.java` | `aabd1cbb3d2b` | 11 | full |
| 407 | `notification-service/src/main/java/com/shop/notificationservice/service/sender/NotificationSenderConfig.java` | `550e75599ce4` | 21 | full |
| 408 | `notification-service/src/main/java/com/shop/notificationservice/service/sender/SmtpNotificationSender.java` | `149719a2f1a9` | 86 | full |
| 409 | `notification-service/src/main/resources/application.yml` | `52f9a0b623c3` | 62 | full |
| 410 | `notification-service/src/main/resources/db/changelog/changelog-001-notifications.yaml` | `647a3d3ef10c` | 43 | full |
| 411 | `notification-service/src/main/resources/db/changelog/changelog-002-notification-retry.yaml` | `c282ce88a409` | 44 | full |
| 412 | `notification-service/src/main/resources/db/changelog/changelog-003-retry-index.yaml` | `f12e2b9572d9` | 7 | full |
| 413 | `notification-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `9a6b2c01415f` | 10 | full |
| 414 | `notification-service/src/test/java/com/shop/notificationservice/NotificationFlowIT.java` | `e65426847143` | 321 | full |
| 415 | `notification-service/src/test/java/com/shop/notificationservice/config/TestLiquibaseConfig.java` | `abb046cfe853` | 22 | full |
| 416 | `notification-service/src/test/java/com/shop/notificationservice/controller/BackofficeNotificationControllerTest.java` | `222f61139f72` | 178 | full |
| 417 | `notification-service/src/test/java/com/shop/notificationservice/dto/OrderLifecycleEventTest.java` | `1c7488ed2748` | 101 | full |
| 418 | `notification-service/src/test/java/com/shop/notificationservice/entity/NotificationEntityTest.java` | `a4202d6bf90c` | 17 | full |
| 419 | `notification-service/src/test/java/com/shop/notificationservice/scheduler/NotificationRetryIT.java` | `7849abd6075a` | 137 | full |
| 420 | `notification-service/src/test/java/com/shop/notificationservice/scheduler/NotificationRetrySchedulerTest.java` | `f2936a255df7` | 141 | full |
| 421 | `notification-service/src/test/java/com/shop/notificationservice/service/NotificationDeliveryServiceTest.java` | `d1db2b13a5a8` | 127 | full |
| 422 | `notification-service/src/test/java/com/shop/notificationservice/service/NotificationRetryPolicyTest.java` | `4b3f05fd9c74` | 48 | full |
| 423 | `notification-service/src/test/java/com/shop/notificationservice/service/NotificationTemplatesTest.java` | `fbf7f11628eb` | 143 | full |
| 424 | `notification-service/src/test/java/com/shop/notificationservice/service/NotificationWriterTest.java` | `062d52566754` | 263 | full |
| 425 | `notification-service/src/test/java/com/shop/notificationservice/service/impls/NotificationServiceImplTest.java` | `726037a8eff7` | 313 | full |
| 426 | `notification-service/src/test/java/com/shop/notificationservice/service/sender/NotificationFailureClassifierTest.java` | `4542eaf18d53` | 85 | full |
| 427 | `notification-service/src/test/java/com/shop/notificationservice/service/sender/NotificationSenderConfigTest.java` | `df032d5d0949` | 35 | full |
| 428 | `notification-service/src/test/java/com/shop/notificationservice/service/sender/SmtpNotificationSenderTest.java` | `1a7a54185394` | 92 | full |
| 429 | `notification-service/src/test/java/com/shop/notificationservice/support/AbstractIntegrationTest.java` | `cdaca0c6208f` | 105 | full |
| 430 | `notification-service/src/test/java/com/shop/notificationservice/support/NotificationBootstrapIT.java` | `2b6ac5bda650` | 67 | full |

---

# 2. DOMAIN MODEL & BOUNDED CONTEXT PER SERVICE

## 2.0 Cross-service shape (the fleet's actual architecture)

Every one of the eight services follows **one** layered template. There is no hexagonal/ports-and-adapters discipline anywhere in scope:

```
controller (HTTP, @PreAuthorize, DTO binding)
   ↓
service/impls/*ServiceImpl  (the "domain" — @Transactional, business rules)
   ↓
repository/*Repository extends JpaRepository  (Spring Data)
   ↓
entity/*  (@Entity, @SQLRestriction, Lombok @Data-style accessors)
```

**Domain layer vs application layer distinction: it does not exist.** The `service` package holds transaction management, business rules, remote-HTTP clients, caching and event publishing *simultaneously*. There is no `domain/` package, no aggregate base class, no value objects, no domain events as first-class objects (the "domain events" are JSON payload maps built inline — see `TransactionalProductEventPublisher.java:68-90`).

**Repository ports vs JPA adapters: only Spring Data repositories exist.** No interface/impl split, no in-memory fake port, no `DomainRepository` abstraction. Every repository is `extends JpaRepository<…>`.

### Where the persistence framework leaks into the domain layer

| Location | Leak | Impact |
|---|---|---|
| `product-service/.../entity/Product.java:14` | `@SQLRestriction("deleted = false")` on the entity | Hibernate-specific annotation in the entity; the soft-delete filter is invisible to the domain model. A domain `Product` has no notion of "excluded because deleted". |
| `product-service/.../entity/Product.java:14`, `Category.java`, `Brand.java`, `ProductVariant.java`, `media-service/.../entity/Media.java`, `rating-service/.../entity/Rating.java`, `tax-service/.../entity/TaxRate.java`, `TaxClass.java` | `@SQLRestriction` everywhere | Every read is silently filtered; there is no way to ask "give me the deleted row" except through a bespoke native query. |
| `product-service/.../service/impls/ProductServiceImpl.java:32` | `import org.springframework.data.jpa.domain.Specification;` — the **application service** speaks JPA `Specification` | The service layer's query API is a JPA type. `ProductFilter` (a DTO) is translated to a JPA `Specification` inside the service. Any non-JPA persistence adapter could not satisfy this signature. |
| `promotion-service/.../repository/CouponUsageReservationRepository.java:28-31` | JPQL string `"select coalesce(sum(r.discountAmount), 0) from CouponUsageReservation r …"` inside the repository interface | Query language leaks into the persistence port; no specification/predicate object. |
| `promotion-service/.../repository/CouponUsageReservationRepository.java:69-70` | JPQL bulk `UPDATE … SET r.version = r.version + 1` — the version bump is *hand-written into the query* | The optimistic-locking contract is duplicated between the entity (`@Version`) and the hand-written JPQL; the two can drift. |
| `media-service/.../repository/MediaRepository.java:40-44` | native `UPDATE` with a hand-written `WHERE id=:id AND deleted=false` | Same class of leak: a native SQL fragment encodes the soft-delete predicate that `@SQLRestriction` also encodes — two sources of truth for one rule. |
| `rating-service/.../service/RatingEventService.java:56` | `ratingRepository.findAggregateByProductId(...).get(0)` returns `Object[]` and the service casts `aggregate[0]`/`aggregate[1]` by hand (`:57-58`) | The domain aggregate (avg rating) is computed by a JPQL aggregate query and consumed as untyped `Object[]` — the shape of the result is a *persistence* contract the service must know. |
| `rating-service/.../entity/Rating.java` + `rating-service/.../service/impls/RatingServiceImpl.java:87` | `findByUserIdAndProductIdAndDeletedFalse` — the **method name** encodes the persistence filter | The domain rule "one rating per (user, product)" is expressed as a Spring Data derived-query name. |
| `search-service` — *no database at all* | no `db/changelog` directory exists; `application.yml` is 36 lines | Search is a pure projection. This is the **one** service in scope with genuine hexagonal boundaries: `ProductSearchService` (`:36-53`) talks only to the `ElasticsearchClient` port and never to a DB. |

### Where the persistence framework does NOT leak (credit)

- **`search-service`** — `ProductSearchService.java:34` depends on the `ElasticsearchClient` *interface*, not on a repository. `ReindexServiceImpl.java:46-49` depends on `ElasticsearchClient` + `ProductBackofficeClient` (an HTTP port). No ORM anywhere. This is a genuinely clean adapter boundary.
- **`media-service` storage boundary** — `MediaUploadServiceImpl.java:47` depends on the `ObjectStorageService` **interface** (`utils/common-storage`), not on the S3 client. `MediaQueryServiceImpl`, `MediaPurgeJob.java:22` and `BucketBootstrap` all speak the same port. A second storage backend is a one-line swap.
- **`favourite-service`** — `FavouriteRepository.java:28,38-50,56-68` are *atomic, owner-scoped* derived/native queries (`findByIdAndUserId`, `softDeleteByIdAndUserId`, `softDeleteByUserIdAndProductId`). The owner-scoping is enforced **in SQL**, not in Java — a correct DDD instinct expressed in the persistence adapter.
- **Transactional outbox ports** — the outbox *repository* is a port (`OutboxEventRepository`), the *relay* is an adapter over `KafkaMessagePublisher`. The separation between "write the intent" and "deliver the intent" is clean everywhere.

## 2.1 product-service — *Catalog* bounded context

- **Aggregates:** `Product` (root: `title`, `slug`, `sku`, `priceUnit`, `quantity`, `status`, `mediaId`, `imageUrl`, `avgRating`, `ratingCount`, `@ManyToOne Category`, `@ManyToOne Brand`) — `entity/Product.java:22-77`. `Category` (self-referencing tree via `parent`/`children`) — `entity/Category.java:25-43`. `Brand` — `entity/Brand.java:22-34`. `ProductVariant` — `entity/ProductVariant.java:35-58`.
- **Invariants (as implemented):** slug/SKU uniqueness on live rows (`ProductRepository.existsBySlugAndIdNot`); category parent must exist (PRD-2003) and must not create a cycle (PRD-2009, `CategoryServiceImpl.java:127-143`); brand must exist (PRD-2006); a product with live stock reservations cannot be deleted (PRD-2010); a product whose media-service is unreachable cannot be deleted (PRD-2011).
- **Domain vs application:** merged (see §2.0). Business rules live in `service/impls/ProductServiceImpl.java`, `CategoryServiceImpl.java`, `BrandServiceImpl.java`, `ProductVariantServiceImpl.java`.
- **Repositories:** `ProductRepository`, `CategoryRepository`, `BrandRepository`, `ProductVariantRepository`, `OutboxEventRepository` — all Spring Data.

## 2.2 search-service — *Search projection* bounded context (read-model only)

- **No entities, no aggregates, no invariants.** The only model is the ES document, assembled by `ProductDocuments.of(...)` from the event payload.
- The "aggregate" is the **product document** keyed by `productId` (`ProductSearchService.java:45`).
- "Domain service" = `ProductSearchService` (write side) + `SearchQueryServiceImpl` (read side); "reindex" = `ReindexServiceImpl` (bulk rebuild); "provisioning" = `IndexProvisioner`.
- **This is the cleanest bounded context in scope** — and also the one with the most consequential defect (§4).

## 2.3 media-service — *Media asset* bounded context

- **Aggregate:** `Media` (root) with an owned collection of `MediaVariant` — `entity/Media.java:44-62`, `@OneToMany(cascade=ALL, orphanRemoval=true, fetch=EAGER)`. `MediaVariant` is the value-ish child: `variant` (original/display/thumb) × `format` (jpeg/png/webp), `width`, `bytes`, `objectKey` — `entity/MediaVariant.java:28-30` carries `uk_media_variant_format` on `(media_id, variant, format)`.
- **Invariants:** exactly one row per `(media, variant, format)`; the SHA-256 is content-addressed and unique; a media that a live product references cannot be soft-deleted (MED-12007); a soft-deleted media is hard-deleted only after the grace window AND only when unreferenced.
- **Domain vs application:** `service/impls/MediaUploadServiceImpl`, `MediaLifecycleServiceImpl`, `MediaQueryServiceImpl`, `VariantRenderer`, `MediaFormats`, `MediaMetadataInspector` — all mixed with I/O, storage and transactions.
- **Repositories:** `MediaRepository` (Spring Data + two native queries at `:40-44`, `:54-55`, `:66-69`), `OutboxEventRepository`.

## 2.4 promotion-service — *Promotion / Coupon* bounded context

- **Aggregates:** `Campaign` (root: `code`, `discountType`, `discountValue`, `minOrderAmount`, `startsAt`, `endsAt`, `maxRedemptions`, `totalBudget`, `perUserLimit`, `status`, `@Version version`) — `entity/Campaign.java:21-66`. `CouponUsageReservation` (a **reservation** aggregate, not a coupon entity) — `entity/CouponUsageReservation.java:40-76`.
- **Invariants:** the campaign code is unique among live rows (`uk_campaign_code_live`); reservation status ∈ {PENDING, COMMITTED, RELEASED, EXPIRED} (`ck_cur_status`); one reservation per order (`uk_cur_order_id`); a reservation expires `reservedAt + TTL`; committed reservations are idempotent.
- **Key modelling insight (credit):** the service models *reservations* with an explicit lifecycle and an **optimistic-locking version column** on both aggregates (`Campaign.java:61`, `CouponUsageReservation.java:76`), plus a **hand-written conditional-UPDATE claim** for the expiry sweep (`CouponUsageReservationRepository.java:68-74`). This is the most DDD-conscious of the eight.

## 2.5 tax-service — *Tax* bounded context

- **Aggregates:** `TaxClass` (root, `defaultRatePct`) — `entity/TaxClass.java:21-33`; `TaxRate` (belongs to a class, keyed by `country` + `postalCode`) — `entity/TaxRate.java:23-42`. Both carry `@Version`.
- **Invariants:** rate ∈ [0, 100] (CHECK); unique live `(tax_class_id, country, coalesce(postal_code,''))` — `changelog-001-initial-schema.yaml:70`.
- **The tax rule itself is a pure function** — `TaxCalculator.java:13-16` — which is the one piece of genuinely pure domain logic in scope.

## 2.6 rating-service — *Rating* bounded context

- **Aggregate:** `Rating` (root: `productId`, `userId`, `rating`, `comment`, `verified`, `hidden`, `hiddenAt`, `hiddenBy`, `hiddenReason`, `editedAt`) — `entity/Rating.java:30-63`.
- **Invariants:** rating ∈ [1,5]; comment length ∈ [5,2000]; at most **one live rating per (user, product)** — `changelog-001-ratings.yaml:42`; only a verified buyer may submit.
- **The aggregate rating is NOT stored on the Rating aggregate** — it is a live DB aggregate (see §7).

## 2.7 favourite-service — *Favourite* bounded context

- **Aggregate:** `Favourite` — `entity/Favourite.java:32-38` (`userId`, `productId`). Smallest aggregate in scope; the only interesting invariant is uniqueness.
- **Repository** is the enforcement point (`idx_favourites_user_product_unique_active`, `changelog-001:36-39`) with a `DataIntegrityViolationException` catch as the second line of defence.

## 2.8 notification-service — *Notification delivery* bounded context

- **Aggregate:** `Notification` — `entity/Notification.java:41-83` (`eventId`, `orderId`, `userId`, `eventType`, `status`, `retryCount`, `nextRetryAt`, `lastError`, `channel`, `subject`, `body`, `payload`).
- **State machine:** `NotificationStatus` (7 states) with `NotificationWriter.java:39-104` guarding every transition against the current status.
- **Invariants:** one notification per `eventId` (`uk_notification_event_id`); retry attempts bounded (`max-attempts: 6`); a claim is leased by a `SENDING` heartbeat.

---

---

# 3. PRODUCT SERVICE — catalog aggregate, money, outbox

## 3.1 The catalog aggregate

| Element | Location | Notes |
|---|---|---|
| `Product` (root) | `entity/Product.java:20-78` | `@Entity`, `@SQLRestriction("deleted = false")`, `@GeneratedValue(strategy = UUID)` id |
| price | `entity/Product.java:38-39` | `@Column(name="price_unit", nullable=false, precision=15, scale=2) BigDecimal priceUnit` |
| variant price | `entity/ProductVariant.java:47-48` | `@Column(precision=15, scale=2) BigDecimal price` |
| weight | `entity/Product.java:55-56` | `@Column(precision=8, scale=3) BigDecimal weight` |
| avg rating | `entity/Product.java:62-63` | `@Column(name="avg_rating", precision=3, scale=2)` — a *snapshot copied from rating-service*, never recomputed here (`Product.java:61` comment) |
| category | `entity/Product.java:71-73` | `@ManyToOne(LAZY)` |
| brand | `entity/Product.java:75-77` | `@ManyToOne(LAZY)` |
| media reference | `entity/Product.java:52-53` | `@Column(name="media_id") UUID mediaId` — a *reference by id* to another bounded context. Correct: media-service owns the asset. |
| derived image | `service/impls/ProductMapper.java:97-101` | `mediaId != null → "/api/v1/medias/" + mediaId`, else the legacy free-text `imageUrl`. The canonical path is **never stored** — computed at mapping time. **Credit.** |

**Media reference is a genuine cross-context relationship done right**: product-service stores only the foreign id, never the media URL; it derives the canonical path; it verifies the reference exists via a remote HEAD (`client/MediaHeadClient.java:47-62`) before accepting a write.

## 3.2 Money handling

**Scale/rounding.** Two `BigDecimal` money columns, both `precision=15, scale=2` (`Product.java:38-39`, `ProductVariant.java:47-48`). The scale is enforced at the *column* level; the *domain* never rounds a price — the DTO carries `BigDecimal priceUnit` straight through the mapper to the entity (`service/impls/ProductMapper.java`, `dto/request/ProductCreateRequest`). No `setScale(2, …)` is applied on write, so a caller supplying `999.999` is stored as `999.999` in Java and truncated/rounded by Postgres to `999.99` on the column. There is no explicit `RoundingMode` anywhere in product-service.

**Currency: THERE IS NO CURRENCY.** A repo-wide case-insensitive search for `currency` across all eight services' `.java`, `.yaml` and `.yml` returns **7 hits, all of them the word "Concurrency" in comments and test-class names** (`PromotionOutboxClaimConcurrencyIT`, `ReindexServiceImpl.java:32`, etc.). **Zero** currency columns, zero currency fields, zero currency codes, zero ISO-4217 handling.

Consequence: `price_unit` is an unqualified decimal. The same column carries USD, VND, EUR and JPY amounts with no way to distinguish them. `TaxCalculator.java:13-16` in tax-service also has no currency on its result, and promotion-service's `discountAmount`/`orderAmount`/`totalBudget` (`entity/CouponUsageReservation.java:52-55`, `entity/Campaign.java:36`) have none either. This is a **fleet-wide money-model defect** (CAT-05), not a product-service-only one — but product-service is where the catalog's canonical price lives.

## 3.3 Outbox emission for product / category / brand changes

### 3.3.1 Is the outbox insert in the same transaction as the business write? — YES, and it is proven.

The publisher is **deliberately not** `@Transactional`; it joins the caller's transaction.

`product-service/.../service/impls/TransactionalProductEventPublisher.java:22-31` (javadoc):
> "The publisher itself is intentionally NOT `@Transactional`: the caller's transaction (e.g. `ProductServiceImpl.create/update/delete`) provides the boundary. If no transaction is open the row would still be committed individually, defeating atomicity — by participating in an existing transaction we guarantee the entity change and the outbox row either both commit or both roll back."

The actual write is `product-service/.../service/impls/TransactionalProductEventPublisher.java:99`:
```java
outboxRepository.save(event);
```

The caller is `service/impls/ProductServiceImpl.java` — `@Transactional` on `create`/`update`/`delete`. Because `outboxRepository.save()` runs inside the still-open caller transaction, the entity row and the outbox row commit or roll back together.

**Same pattern, same proof:**
- `service/impls/TransactionalCategoryEventPublisher.java:81` — `outboxRepository.save(event)`, called from `CategoryServiceImpl` (`@Transactional` on `create`/`update`/`delete` at `CategoryServiceImpl.java:78-95`, `:98-116`, `:147-156`).
- `service/impls/TransactionalBrandEventPublisher.java:80` — called from `BrandServiceImpl.java:56-66` (create), `:68-81` (update), `:83-92` (delete).

**Integration proof that this actually holds** (not just javadoc): `product-service/src/test/.../service/ProductRatingOutboxIntegrationTest.java:35-74` asserts that after `ratingService.apply(...)` returns, a **PENDING `ProductUpdated` outbox row** is already committed in the same Postgres transaction as the product row. `OutboxRelayIntegrationTest.java:59-61` asserts the same for `productService.create(req)`.

### 3.3.2 What is NOT emitted — variants (CAT-06)

`product-service/.../service/impls/ProductVariantServiceImpl.java` — `grep` for `publish` / `Event` in this file returns **nothing**. Variant create/update/delete mutate `product_variants` and publish **no** lifecycle event.

Consequence: variants never reach search-service, never reach the ES index, and never reach any consumer. A catalogue that prices per-variant (`product_variants.price`) has no searchable representation of that price. `product-service/src/main/resources/db/changelog/changelog-006-product-variants.yaml:7` creates the table; nothing propagates it.

### 3.3.3 Outbox relay mechanics (credit)

- **Claim:** `repository/OutboxEventRepository.java:35-39` — `@Lock(PESSIMISTIC_WRITE)` + `@QueryHint(name="jakarta.persistence.lock.timeout", value="-2")` (= `FOR UPDATE SKIP LOCKED`) on a MIN(id) subquery. A concurrent claimer gets **empty**, never the same row. Proven by `ProductOutboxClaimConcurrencyIT.java:56-185`.
- **Mark phase is a compare-and-set** under the same lock: `repository/OutboxEventRepository.java:53-56` `findByIdForUpdate`.
- **Ordering:** `service/OutboxRelay.java:245` `orderById` — the claimed batch is published in ascending id order, and a failed record blocks only the *same aggregate's* later records (`OutboxRelayTest.java:143-168`).
- **Stranded-claim recovery:** `repository/OutboxEventRepository.java:73-84` `reclaimStaleSending` — a bulk conditional UPDATE that returns a stale `SENDING` row to `PENDING` (or parks it at `FAILED` when the retry budget is burnt). Driven by the relay, not by a stray method: `service/OutboxRelay.java:191` `recoverStrandedClaims()`. Proven by `OutboxRelayRecoveryIT.java:68-85`.
- **Retention:** `service/OutboxRetentionScheduler.java:32-34` `@Scheduled(cron="${product.outbox.retention-cron:0 0 3 * * *}") @Transactional` purges `SENT` rows older than 7 days via `OutboxEventRepository.java:94-97`.

### 3.3.4 product-service outbox weaknesses (CAT-09, CAT-10)

1. **No dedicated claim-lease column.** `entity/OutboxEvent.java:18-55` has `sentAt` and `lastError` but **no `claimed_at`** and **no `failed_at`**. The claim lease is the `@LastModifiedDate` `updated_at` column inherited from `AbstractMappedEntity` — an *audit* column doubling as a *lease* column. The test itself admits it: `OutboxRelayRecoveryIT.java:183-188` "This service's outbox row has no dedicated claim column — the relay's claim is what refreshes `updated_at`". media-service (`changelog-004-media-outbox-claim-lease.yaml`), rating-service (`changelog-002-rating-outbox-claim-lease.yaml`) and promotion-service all have a real `claimed_at`.
2. **No `failed_at`, therefore no DEAD aging.** `OutboxStatus` has a `DEAD` value (`utils/common-core/.../OutboxStatus.java`) but product-service's relay never produces it: `service/OutboxRelay.java:218-222` sets `PENDING` or `FAILED` only, and the retention scheduler purges `SENT` only (`OutboxRetentionScheduler.java:36-37`). A `FAILED` product event is **terminal and invisible** — it sits in the table forever with no `DEAD` marker, no age, no metric. media-service solves this properly (`outbox/MediaOutboxRetentionScheduler.java:26-46` + `ageDeadFailedBefore`).

### 3.4 Caching (product)

`config/CacheConfig.java:62-68` customises the Redis cache manager with `immediateWrites()` (so `@CachePut` is visible on read) and per-cache TTLs (product 10 m, taxonomy 30 m). Cache eviction on delete is verified: `ProductServiceImplTest.java:244-258` asserts `productCache.evict(ID)` and `slugCache.evict("iphone-15")` and nothing else.

## 3.5 Remote calls and transaction boundaries (credit — F-066)

`ProductServiceImpl` performs a **remote HEAD** to media-service. The design keeps that call **outside** the transaction:

- `service/impls/ProductServiceImpl.java:267` `assertMediaExists(UUID mediaId)` is called *before* the transactional write begins.
- Proven against a real context: `src/test/.../service/impls/ProductServiceImplTransactionBoundaryIT.java:44-49` asserts `TransactionSynchronizationManager.isActualTransactionActive()` is **false** inside the mocked `MediaHeadClient.exists(...)`. The javadoc at `:21-29` explains that a unit test cannot prove this because `@Transactional` is inert without a Spring context — the IT is the real proof. **This is exemplary.**

`client/MediaHeadClient.java:47-62` — 200 → `true`; 404 → `false`; anything else (5xx, connection refused) → `MEDIA_STORAGE_UNAVAILABLE` 503. **Fail-closed**: the write fails, it never silently accepts an unverified reference. `MediaHeadClientTest.java:98-120` pins the 503 for both connection-refused and 5xx; `MediaHeadValidationIT.java:171-199` pins it end-to-end over real HTTP.

`client/InventoryReservationClient.java:53-71` — same fail-closed discipline for the delete-time stock guard: any unreadable answer is `OptionalLong.empty()` = UNKNOWN, and `ProductServiceImpl.assertNoLiveStockReservations` (`:242`) refuses the delete. `InventoryReservationClientTest.java:74-113` proves that 500, IOException, malformed body **and 404** all yield UNKNOWN — a 404 (endpoint missing) is deliberately *not* read as "no reservation".

---

---

# 4. SEARCH SERVICE — Kafka → Elasticsearch synchronization

## 4.1 How is the index updated?

**Path:** `shop.product.lifecycle.v1` → `ProductSearchConsumer` → `ProductSearchService` → `ElasticsearchClient.index()`.

`search-service/.../kafka/ProductSearchConsumer.java:36-40`:
```java
@KafkaListener(topics = KafkaTopicContract.SHOP_PRODUCT_LIFECYCLE, groupId = "search-service",
        containerFactory = "searchListenerFactory")
public void onMessage(String rawValue, MessageHeaders headers) {
    processMessage(rawValue, headers, this::handleContained);
}
```

`search-service/.../kafka/ProductSearchConsumer.java:55-60` — the dispatch:
```java
switch (event.eventType() == null ? "" : event.eventType()) {
    case "ProductCreated", "ProductUpdated" -> productSearchService.index(event);
    case "ProductDeleted" -> productSearchService.delete(event.productId());
    default -> log.info("Skipping unknown product eventType {} (eventId={})", ...);
}
```

`search-service/.../service/ProductSearchService.java:36-53` — the write:
```java
public void index(ProductLifecycleEvent event) {
    if (!STATUS_ACTIVE.equals(event.status())) {
        delete(event.productId());
        return;
    }
    Map<String, Object> document = ProductDocuments.of(event);
    try {
        client.index(i -> i
            .index(IndexProvisioner.ALIAS)
            .id(event.productId().toString())
            .document(document));
    } catch (IOException | ElasticsearchException ex) {
        throw BusinessException.of(ErrorCode.SEARCH_INDEX_FAILED, event.productId());
    }
}
```

**Status handling is bidirectional (credit).** A non-ACTIVE payload *deletes* the document (`ProductSearchService.java:37-40`), which covers DRAFT→ACTIVE re-publish as well as ACTIVE→ARCHIVED. `ProductSearchIngestionIT.java:164-197` proves both directions against a real ES container, including a `null` `avgRating`/`ratingCount` re-publish.

## 4.2 Document id — STABLE ✅

`ProductSearchService.java:45` — `.id(event.productId().toString())`. The document id is the **product's own UUID**, not a derived/hash value. A product always maps to exactly one document, forever. This is the correct choice: re-delivery of the same event overwrites the same document (idempotent), and there is no way for a product to acquire a second document.

`ReindexServiceImpl.java:114` uses the same id (`document.get("id").toString()`) so a rebuilt index and the live index agree.

## 4.3 Duplicate and OUT-OF-ORDER event handling — **NEITHER IS HANDLED** (CAT-01)

**Duplicates:** handled *incidentally* — a stable document id means a redelivered event overwrites the same document with the same content. Idempotent by construction. ✅

**Out-of-order: NOT handled at all.** The `client.index(...)` call at `ProductSearchService.java:43-46` passes **no** version, no `if_seq_no`/`if_primary_term`, no `version_type`, no `external_gte`.

**Proof of absence.** A case-insensitive grep for `version|seq_no|seqNo|if_seq_no|external|externalVersion` across `search-service/src/main/java` returns **0 hits**. The `ProductLifecycleEvent` record *does* carry `occurredAt` and `updatedAt` fields — and neither is read for ordering purposes; they are copied into the document as data (`IndexProvisioner.java:76` maps `updatedAt` to a `date` field) and never used as a precondition.

**Consequence.** Elasticsearch's default `index` operation is an unconditional last-write-wins. If a `ProductUpdated` event with `price=39.99` is delivered *before* an older `ProductUpdated` with `price=25.50` (possible: the product-service relay publishes per-aggregate in ascending id order, but Kafka rebalancing, redelivery after a consumer restart, and multiple producer instances all break that ordering), the older event **overwrites the newer state**. The index then permanently shows a stale price/title/description, and no mechanism — no retry, no reconciliation, no reindex trigger — will ever correct it.

**This is provably not covered by the test suite.** `search-service/src/test/.../kafka/ProductSearchIngestionIT.java` has 11 tests: create-indexes-doc, legacy-double-encoded-accepted, update-replaces-doc, active→non-active-deletes, non-active→active-republishes, delete-removes, delete-of-never-indexed-is-a-no-op, unknown-eventType-skipped, null-eventType-skipped, malformed-JSON-does-not-throw. **There is no out-of-order test** — no test publishes a newer event and then an older one for the same product and asserts the newer state survives.

**Not a mitigation:** `ReindexServiceImpl` can rebuild the index from source, but it is a **manual, operator-triggered** endpoint (`ReindexServiceImpl.java:35-36`: "NO auto full-sync on startup (ops §4(1)): the first deploy runs reindex once via this endpoint"). It is not a recovery path for a stale document.

## 4.4 Reindex / recovery path

`search-service/.../service/impls/ReindexServiceImpl.java`:
- `nextIndexName()` (`:131-148`) — resolves `max(products-v*)+1`, skipping unparseable names.
- `createIndex(newIndex)` (`:154-161`).
- `streamSource(sink)` (`:85-97`) — pages the backoffice source API at `SOURCE_PAGE_SIZE = PageableConstant.MAX_PAGE_SIZE` (`:44`).
- `bulkIndex(newIndex, snapshots)` (`:104-129`) — one `client.bulk(...)` per page; **any** item error aborts with `SEARCH_QUERY_FAILED` (`:118-124`).
- `refresh(newIndex)` (`:163-170`).
- `swapAlias(newIndex)` (`:176-186`) — a single `updateAliases` removing the alias from `products-v*` and adding it to the new index. **Atomic** ✅.
- `deleteSupersededIndices(newIndex)` (`:189-201`) — deletes every `products-v*` except the new target, which also reaps orphans left by a failed run.

**Failure semantics (credit):** `ReindexServiceImpl.java:26-31` — "any source-page failure or ES failure mid-stream maps to 503 SRH-12002 and aborts WITHOUT an alias swap." The old alias target keeps serving traffic. The orphaned partial index is left behind and reaped by the next successful run's cleanup (`:189-201`).

**Concurrency: in-process only (CAT-08).** `ReindexServiceImpl.java:49`:
```java
private final AtomicBoolean reindexInProgress = new AtomicBoolean(false);
```
`:53` rejects a second concurrent run with 409 `SRH-12001`. The javadoc at `:32-34` is explicit that this is a "single-instance MVP posture — a multi-node deployment needs a distributed lock first". **In a multi-replica deployment, two pods can each run a reindex simultaneously**: both compute a `nextIndexName()` from the same `max(products-v*)` → both create `products-vN+1` (the second `create` fails with `resource_already_exists_exception` → 503, so that specific collision is caught), but more dangerously both can stream and both can `swapAlias` — the second swap removes the alias from the index the first just published and points it at its own index. The end state is a valid but *unreviewed* index. The AtomicBoolean gives zero protection across JVMs.

**Source client is fail-fast, no retry.** `client/ProductBackofficeClient.java:50-70` — a source-page failure aborts the whole reindex (correct) but there is no backoff; a transient blip on page 1 fails the whole operation.

## 4.5 Event for a deleted product — ✅ PRESENT

- Producer side: `TransactionalProductEventPublisher.java:56-58` `publishDeleted` → eventType `"ProductDeleted"`, topic `shop.product.lifecycle.v1`.
- Consumer side: `ProductSearchConsumer.java:57` `case "ProductDeleted" -> productSearchService.delete(event.productId());`
- `ProductSearchService.java:55-71` `delete(...)` — `client.delete(d -> d.index(ALIAS).id(productId.toString()))`. A 404 (`Result.NotFound` at `:58`, or `ElasticsearchException` with `status() == 404` at `:62-66`) is treated as a no-op with a debug log; any other ES failure raises `SEARCH_DELETE_FAILED`.
- Proven: `ProductSearchIngestionIT.java:199-222` — delete removes the doc, and deleting a never-indexed product is a clean no-op that does not stall the partition.

## 4.6 ES client version actually used — **DEPENDENCY DRIFT** (CAT-02)

`search-service/pom.xml:36-40`:
```xml
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <version>8.15.5</version>
</dependency>
```

The **root** POM manages the version:
`pom.xml:55` → `<elasticsearch-client.version>9.4.5</elasticsearch-client.version>`
`pom.xml:206-207` → `<artifactId>elasticsearch-java</artifactId><version>${elasticsearch-client.version}</version>`

search-service **overrides** the managed version with `8.15.5`. Every other service inherits `9.4.5`. The whole fleet therefore runs against two different major versions of the ES Java client, and the search service is the one on the *older* major line — a client major-version skew against an ES 8/9 server is exactly the combination the elastic client refuses to support in places (and the elastic client's own compatibility matrix makes 8.x-client → 9.x-server a non-certified combination).

**Evidence of intent:** `search-service/src/main/java/com/shop/searchservice/config/ElasticsearchConfig.java:28-42` builds the low-level Apache `RestClient` by hand and `:45-48` wraps it in an `ElasticsearchClient` via `RestClientTransport` + `JacksonJsonpMapper`. The hand-rolled wiring is what makes the 8.x API surface usable, and it is why the drift was not caught: the code compiles and runs against the pinned 8.15.5.

## 4.7 Is the index mapping app-created or must it pre-exist? — **APP-CREATED** ✅

`search-service/.../search/IndexProvisioner.java` implements `ApplicationRunner` (`:18`) and provisions everything at startup:

- `:38-46` — creates the index **template** `products-template` with `indexPatterns("products-v*")` and the full mapping.
- `:48-51` — creates the index `products-v1` if absent.
- `:53-56` — attaches the alias `products` → `products-v1` if absent.
- `:59-78` `productMapping()` — the mapping itself.

Idempotency is proven: `SearchIndexProvisioningIT.java:28-38` re-runs `provision()` against a live container and asserts exactly one alias target, the index still present, the template still present.

**Failure handling (credit, with one caveat):** `IndexProvisioner.java:30-34` catches every exception and logs "Search index provisioning failed — search stays degraded until Elasticsearch is reachable" — the service **starts** rather than crash-looping when ES is down. Good posture.

**Caveat (CAT-13):** the mapping is only applied to **newly created** indices. `IndexProvisioner.java:48` guards `if (!client.indices().exists(e -> e.index(INDEX)).value())` — if `products-v1` already exists with an older/wrong mapping, it is **never updated**. And `ReindexServiceImpl.createIndex` (`:154-161`) creates `products-vN+1` **without** an explicit mapping — it relies on the `products-template` matching `products-v*` (which it does, `:41`). But if the template was created by an *older* build with a different mapping, every subsequent reindex silently inherits the stale mapping. There is no mapping-version gate and no startup assertion that the live mapping matches the code's expectation.

## 4.8 Mapping quality — money stored as a float (CAT-04)

`IndexProvisioner.java:72`:
```java
fields.put("price", Property.of(p -> p.double_(d -> d)));
```

`double_` is an IEEE-754 64-bit float. `999.99` is not representable exactly; the stored value is the nearest double (999.9899999999999863575794734060764312744140625). Consequences:
- **Range queries on price can be off by a cent at the boundary.** A `price >= 999.99` filter can exclude a product priced exactly 999.99, because the stored double is *slightly below* 999.99.
- The value is **displayed** back to the user as a double. `ProductSearchIngestionIT.java:110` asserts `((Number) doc.get("price")).doubleValue()).isEqualTo(25.50d)` — the test itself converts through `doubleValue()`, which papers over the representation error.
- ES has a purpose-built `scaled_float` type for exactly this (money as an integer with a scaling factor) — it is not used.

`avgRating` uses `halfFloat` (`:73`) — a 16-bit float with ~3 decimal digits of precision. For a 1–5 rating averaged over N ratings this is *marginally* adequate but is still a float where the source is `BigDecimal(precision=3, scale=2)`. `ratingCount` is an `integer` (`:74`) ✅.

## 4.9 Other search-service observations

- `service/impls/SearchQueryServiceImpl.java:181` `searchDeepWithPit(...)` / `:204` `openPit()` / `:219` `closePit(...)` — Elasticsearch **point-in-time** support for deep pagination. Genuinely the right mechanism for `from`+`size` beyond `index.max_result_window`.
- `dto/request/SearchRequest.java:45-46` — `MAX_RESULT_WINDOW = 10_000`, with the max page number derived from it. Consistent with the PIT path.
- `security/ServiceTokenProvider.java:59` — `private synchronized String refreshToken()`. Correct (mutual exclusion) but **no lock timeout**: a thread blocked on the monitor waits for the full HTTP round trip to Keycloak. product-service, media-service and rating-service all use `ReentrantLock.tryLock(5, TimeUnit.SECONDS)` instead — a bounded wait that degrades to an error rather than an unbounded stall (CAT-14).
- `security/ServiceTokenProvider.java:50-57` — double-checked caching with an `AtomicReference<CachedToken>` (`:35`) and a 30-second refresh skew (`:28`). ✅
- `client/ProductBackofficeClient.java` — the reindex source. `ProductBackofficeClientTest` pins the wire contract.

---

---

# 5. MEDIA SERVICE — MIME validation, image pipeline, object storage

## 5.1 MIME magic-byte validation — **THE DECLARED Content-Type IS NOT TRUSTED** ✅

`media-service/.../service/impls/MediaUploadServiceImpl.java:62-82` — the whole gate:
```java
String format = MediaFormats.formatOfContentType(file.getContentType());
if (format == null) {
    log.warn("Upload rejected: content type '{}' not in allowlist", file.getContentType());
    metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
    throw BusinessException.of(ErrorCode.MEDIA_TYPE_NOT_ALLOWED);
}

// 2. magic bytes must exist and agree with the declared type (400)
String sniffed = MediaFormats.sniff(headBytes(file));
if (sniffed == null || !sniffed.equals(format)) {
    log.warn("Upload rejected: declared '{}' but magic bytes say '{}' (corrupt or lying upload)",
            file.getContentType(), sniffed);
    metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
    throw BusinessException.of(ErrorCode.MEDIA_INVALID_FILE);
}

if (file.getSize() > properties.maxUploadBytes()) {
    log.warn("Upload rejected: {} bytes exceed cap of {} bytes", file.getSize(), properties.maxUploadBytes());
    metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
    throw BusinessException.of(ErrorCode.MEDIA_TOO_LARGE);
}
```

Three independent conditions must all hold:
1. **Allowlist** — `MediaFormats.formatOfContentType` (`service/impls/MediaFormats.java:37-48`) maps only `image/jpeg`, `image/jpg`, `image/png`, `image/webp`; everything else → `MEDIA_TYPE_NOT_ALLOWED` (415).
2. **Magic bytes must AGREE with the declared type** — `MediaFormats.sniff` (`MediaFormats.java:54-69`):
   - JPEG: `FF D8 FF` (`:58-60`)
   - PNG: `89 'P' 'N' 'G'` (`:61-63`)
   - WebP: `RIFF` at 0-3 **and** `WEBP` at 8-11 (`:64-67`)
   - `null` when fewer than 12 bytes are available (`:55-57`).

   A PNG uploaded as `image/jpeg` → `sniffed="png" != "jpeg"` → **rejected**. A file whose bytes match nothing → `sniffed == null` → **rejected**.
3. **Size cap** — `file.getSize() > properties.maxUploadBytes()` → `MEDIA_TOO_LARGE` (413).

**This is the correct design**: the declared Content-Type is only a *claim*; the magic bytes are the *evidence*; a disagreement is treated as a corrupt/lying upload.

**Proof:**
- `MediaFormatsTest.java:11-45` — unit-level: JPEG/PNG/WebP magic detected; junk and a 2-byte head return `null`; `formatOfContentType` rejects `image/gif` and `text/plain` and `null`.
- `UploadIT.java:212-228` `corruptMagicBytes_returns400Med12001` — real HTTP: junk declared as `image/jpeg` → 400 `MED-12001`; **a valid PNG declared as `image/jpeg` → 400 `MED-12001`**. Nothing stored.
- `UploadIT.java:197-210` `wrongMimeType_returns415Med12003` — `image/gif` and `text/plain` → 415 `MED-12003`.
- `MediaControllerIT.java:158-166` — the same over real HTTP through the real multipart parser.
- `UploadIT.java:304-323` `undecodableButMagicValid_returns415Med12003` — magic-valid but undecodable bytes → 415, not 400. The classification is deliberate (see `MediaUploadServiceImpl.java:112-117`).

## 5.2 Image processing pipeline

**Library:** Thumbnailator 0.4.20 (`Thumbnails`) + ImageIO, with `webp-imageio` 0.10.2 registered as an ImageIO writer for WebP, and `metadata-extractor` 2.19.0 for the EXIF/GPS inspection.

`media-service/.../service/impls/VariantRenderer.java`:
- `MAX_DIMENSION = 8192` and `BYTES_PER_PIXEL = 4` — the constants of the (old) pixel-based guard.
- `render(source, format, displayWidth, thumbWidth)` produces `original` / `display` / `thumb` × (source format + WebP).
- `decode(byte[] source)` (`:95`) — the single decode point.
- `encode(BufferedImage, width, height, format)` (`:147`).

**Object count — proven:** `VariantRendererObjectCountTest.java:36-73`
- A JPEG/PNG upload yields **6** objects (3 variants × {source format, webp}).
- A **WebP** upload yields **3** objects — the two formats coincide, so no duplicate `variant+format` row is written (`:63-73` asserts no duplicate `variant/format` pairs).
- `UploadIT.java:112-120` re-proves the same over real RustFS: `expectedFormats = "webp".equals(format) ? 1 : 2`.

**No upscaling (credit):** `UploadIT.java:327-355` — a 100×80 source keeps its dimensions in *all* variants; no padding, no scaling-up.

**Stored bytes are always a re-encode, never the raw upload (credit, security-relevant):** `UploadIT.java:164-177` `storedOriginalIsNeverRawBytes` — the stored object differs byte-for-byte from the upload.

**EXIF/GPS are stripped (credit):** the pipeline re-encodes, which drops metadata. `UploadIT.java:255-276` `exifAndGpsAreStrippedFromStoredOriginal` — a crafted JPEG carrying a valid EXIF APP1 + GPS IFD is verified to *contain* both before the upload (`:260-262`) and to contain **neither** after (`:269-270`), while remaining a decodable full-resolution image. `MediaMetadataInspector` reads the metadata only to **log** it — it is never stored (`MediaUploadServiceImpl.java:101`).

**Decompression-bomb protection ✅ — but see CAT-11 for the residual gap.** `VariantRenderer` enforces a *decoded-bytes* budget before decoding: `media.max-decoded-bytes` (default 64 MB) covers `width × height × 4` **plus** the source bytes, because both are resident at the same time.

Proof:
- `VariantRendererMemoryBudgetTest.java:51-60` — an image above the budget is rejected **before it is decoded** (`ImageTooLargeException`).
- `VariantRendererMemoryBudgetTest.java:62-72` — the source bytes count towards the budget.
- `MediaUploadMemoryBudgetIT.java:74-88` — over real HTTP with `media.max-decoded-bytes=1MB`: → **413 MED-12002**, and the response body code is `MED-12002`.
- `MediaUploadServiceImpl.java:106-111` maps `ImageTooLargeException` → `MEDIA_TOO_LARGE` (413), and `:112-117` maps `InvalidImageException` → `MEDIA_TYPE_NOT_ALLOWED` (415).

The old pixel guard (8192×8192 = 268 MB per upload) was replaced by the byte budget precisely to stop a concurrent-upload heap exhaustion; the javadoc at `VariantRendererMemoryBudgetTest.java:14-22` records the rationale.

## 5.3 RustFS / S3 upload

**Client:** AWS SDK v2 `S3Client` behind the `ObjectStorageService` port (`utils/common-storage/.../S3ObjectStorageService.java`). Endpoint/credentials/bucket come from `shop.storage.*`; the media bucket additionally from `media.bucket`. `AbstractMediaIntegrationTest.java:71-75` pins both to the same value ("single source of truth"), and `MediaBucketUnificationTest.java` proves every upload/read/purge call uses the **bucket-qualified** overloads (`storage.upload(BUCKET, key, …)`, `storage.presignedGetUrl(BUCKET, key, ttl)`, `storage.delete(BUCKET, key)`) and **never** the default-bucket chain (`MediaBucketUnificationTest.java:112-113`, `:136-137`, `:152-153`, `:168-169`).

### Presigned URL TTL

- Config: `config/MediaProperties.java` — `presignTtl` is a `Duration`; `application.yml:71-81` sets `media.presign-ttl: 7d`.
- Use: `service/impls/MediaQueryServiceImpl.java:40-64` → `storage.presignedGetUrl(bucket, objectKey, properties.presignTtl())`.
- Proven: `MediaQueryServiceImplTest.java:218-224` `resolve_usesConfiguredPresignTtl`; `ProvisioningIT.java:51-58` asserts the bound value is 7 days.
- The URL is a **302 redirect**, not a proxy: `MediaControllerIT.java:170-194` follows the `Location` manually (redirect-following disabled) and confirms MinIO serves the object with `Content-Type: image/webp` and the right width.

**Observation (CAT-12, low):** a 7-day presign TTL is long for a catalogue thumbnail. The URL is bearer-capability — anyone holding it has read access to the object for 7 days. It is a deliberate, configurable choice (`media.presign-ttl`) and not a bug, but the default is generous relative to the 30-minute cache TTL used for catalogue reads.

### Path traversal in the object key — **NOT POSSIBLE** ✅

`MediaUploadServiceImpl.java:224-226`:
```java
private String objectKey(UUID mediaId, String variant, String format) {
    return mediaId + "/" + variant + "." + MediaFormats.extOf(format);
}
```

The key is built from a **server-generated UUID** (`mediaId = UUID.randomUUID()` at `:119`), a **fixed** variant name (`original`/`display`/`thumb`, from the renderer), and a **fixed** extension from a closed map (`MediaFormats.EXTENSIONS`, `MediaFormats.java:17-18`). **No part of the key is derived from user input.** The client-supplied filename is never used — `UploadIT.java:240-242` uploads the same bytes under a different filename (`renamed-copy.jpg`) and the resulting object key is identical. Path traversal is structurally impossible.

### Orphan cleanup / partial-upload failure ✅

`MediaUploadServiceImpl.java:132-147` — objects are written one at a time and tracked in `writtenKeys` (a `LinkedHashSet`, `:132`):
```java
} catch (StorageException e) {
    log.error("Object storage failed mid-upload — purging {} orphan object(s)", writtenKeys.size(), e);
    purgeOrphans(writtenKeys);
    metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
    throw BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
}
```
`purgeOrphans` (`:228-237`) deletes each written key, swallowing per-key failures with a WARN ("left for the purge job").

Persist failure after the S3 writes: `:170-183` — a `DataIntegrityViolationException` (the concurrent-dedup race) purges the orphans and resolves to the **winner's** media (`duplicate=true`); any other `RuntimeException` purges the orphans and rethrows.

**Proof:**
- `UploadIT.java:280-300` `storageFailureMidUploadPurgesOrphansAndPersistsNothing` — the 2nd object write fails → 503 `MED-12006`, **every** written key deleted, nothing stored.
- `UploadIT.java:359-374` `persistFailure_purgesOrphans_noRow` — the row never persists and `deletedKeys` contains exactly `writtenKeys`.
- `MediaUploadServiceImplTest.java:113-129` `persistFailure_purgesOrphansAndRethrows` — a non-race persist failure purges and **rethrows** (does not swallow).
- `MediaUploadServiceImplTest.java:91-111` `dedupRace_resolvesToWinnersMedia_duplicateTrue` — the unique-violation race purges the **loser's** object (asserted to end with `/original.jpg` and to be distinct from the winner's row) and returns the winner's id with `duplicate=true`.
- `MediaOutboxIT.java:207-232` — the same-transaction guarantee holds in the *other* direction: an outbox insert failure **rolls back** the media row (`:216-217`) and rolls back the soft-delete (`:230-231`).

### Max-size enforcement ✅ (two layers, no drift)

- Business cap: `media.max-upload` (10 MB) — `config/MediaProperties.java` (`maxUpload` as a `DataSize`), `application.yml:71-81`.
- Servlet ceiling: `config/UploadLimitsConfig.java:21-31` derives the multipart `maxFileSize` from `media.max-upload` **+ 1 MB headroom** and `maxRequestSize` **+ 2 MB** — a *single source* (`media.max-upload`), so the two cannot drift.
- Proven: `ProvisioningIT.java:60-68` asserts `multipartConfig.getMaxFileSize() == mediaProperties.maxUploadBytes() + 1MB` and `getMaxRequestSize() == maxUploadBytes() + 2MB`.
- Business-level rejection: `UploadIT.java:181-195` — an oversize upload → 413 `MED-12002`, nothing stored.

### Decompression-bomb protection ✅ (see §5.2)

`media.max-decoded-bytes` (64 MB) checked **before** the decode.

### Object-storage ACL — startup-crash on a public bucket (deliberate, CAT-15)

`media-service/.../storage/BucketBootstrap.java`:
- creates the bucket if missing.
- asserts the bucket ACL is private; a **public grant throws `IllegalStateException`**, which aborts startup. `BucketBootstrapTest.java:52-60` `aclViolation_isFatal` asserts `IllegalStateException` with message containing "public ACL grants".
- **Other** failures degrade gracefully: a storage outage at boot → logged, no throw, service still starts (`BucketBootstrapTest.java:62-71`); an ACL *read* outage → also degrades (`:73-80`). Documented at `BucketBootstrap.java:26-32` as a deliberate restart-gated recovery.

**Assessment:** crashing on a *misconfiguration* (public ACL) while tolerating *connectivity* is a defensible security posture — a public media bucket is a data-exposure event that must not start silently. The residual risk is that a transient ACL-read failure is *also* a misconfiguration signal that gets tolerated; the runbook must cover it.

## 5.4 Media lifecycle, reference check, purge

`service/impls/MediaLifecycleServiceImpl.java:47-69` `softDelete`:
1. `mediaRepository.existsIncludingDeleted(id)` → else 404 `MED-12004`.
2. `mediaRepository.findById(id)` → else 409 `MED-12005` (raced delete).
3. **Reference check BEFORE the delete** → if referenced, 409 `MED-12007` (`MEDIA_IN_USE`). A checker that *throws* is treated as REFERENCED (fail-safe) — `:76-84`.
4. `mediaRepository.softDelete(id, actor)` — a **conditional native UPDATE** `WHERE id=:id AND deleted=false` (`repository/MediaRepository.java:40-44`); 0 rows affected → 409 `MED-12005`.
5. `mediaEvents.record(media, MediaDeleted)` in the **same** transaction.

**Proof:** `MediaLifecycleServiceImplTest.java:51-138` (7 tests, incl. `softDelete_unavailableReferenceService_failsSafeAndRefusesTheDelete` at `:95-109` and `softDelete_racedConcurrentDelete_loadGoneMeans409_noEvent` at `:126-138`); `MediaControllerIT.java:258-274` over real HTTP (a referenced media → 409 `MED-12007`, and the *same* media becomes deletable once the reference is gone); `MediaOutboxIT.java:159-232` (the `MediaDeleted` row is committed with the soft delete; a repeat delete writes no second row; an outbox failure rolls the soft delete back).

**Purge job — `service/impls/MediaPurgeJob.java`:**
- `:26` `@Scheduled(fixedDelayString="${media.purge.poll-millis:3600000}")`.
- `:28-29` cutoff = `now - media.purge-grace` (30 d default, `application.yml:71-81`); candidates from `findPurgeableIds(cutoff)`.
- `:36-45` **fail-safe reference gate**: referenced → WARN + skip; **checker throws → treated as REFERENCED** → skip, cycle continues.
- `:50-62` `purgeOne` — **objects first, then rows**, each item in its own try/catch so one failure never aborts the batch. A row-delete failure leaves the objects deleted and the row for the next cycle (`MediaPurgeJobTest.java:192-203`).
- `MediaPurgeIT.java:131-240` proves the grace boundary, the reference gate and the outage path against real Postgres + real RustFS + real HTTP.

**Bucket unification** — `MediaQueryServiceImplTest.java:226-238` proves the read path presigns against `media.bucket` and never touches `defaultBucket()`.

---

---

# 6. PROMOTION SERVICE — coupon / promotion rules

## 6.1 Coupon / promotion rules: **if/else chain — NO Strategy, Specification or Policy** (CAT-16)

`promotion-service/.../service/impls/CampaignReservationServiceImpl.java:54-134` is one linear method with a hard-coded gate order. The javadoc at `:48-53` states the order is load-bearing:
> "Gate order is load-bearing: NOT_FOUND → NOT_ACTIVE → MIN_ORDER → PER_USER → MAX_REDEMPTIONS → BUDGET."

The chain, verbatim:
- `:57-58` NOT_FOUND → `CAMPAIGN_NOT_FOUND` (PRO-7001)
- `:61-65` NOT_ACTIVE — status ≠ ACTIVE **or** `now` before `startsAt` **or** `now` after `endsAt` → `CAMPAIGN_NOT_ACTIVE` (PRO-7004)
- `:66-69` MIN_ORDER → `MIN_ORDER_AMOUNT_NOT_MET` (PRO-7005)
- `:71-82` idempotency by `orderId` → `PROMOTION_DUPLICATE_REQUEST` / `PROMOTION_RESERVATION_INVALID_STATE`
- `:88-92` PER_USER → `PER_USER_LIMIT_EXCEEDED` (PRO-7006)
- `:93-97` MAX_REDEMPTIONS → `BUDGET_EXHAUSTED` (PRO-7007)
- `:98-103` BUDGET → `BUDGET_EXHAUSTED` (PRO-7007)

**Discount computation is also an if/else**, `promotion-service/.../service/DiscountCalculator.java:24-33`: a `switch`/if on `discountType` with `PERCENT` and `FIXED`, `MONEY_SCALE = 2`, `RoundingMode.HALF_UP`. It is a `static` utility with a private constructor — **not** a Strategy.

**Assessment.** For a service with exactly two discount types and six gates, an if/else chain is *defensible* and arguably clearer than six Strategy objects. The real problem is not the shape — it is that the chain is **not extensible without editing the method**, and the method has already grown to 80 lines mixing four concerns (lookup, temporal validity, idempotency, quota accounting, write). The natural refactor is a `List<ReservationGate>` chain, not a per-gate Strategy — the gates are *ordered predicates with side effects*, not interchangeable algorithms. See §11 for the concrete sketch.

## 6.2 Per-coupon usage limit — **racy read-then-write, NOT an atomic DB counter** (CAT-07)

**The quota queries are plain COUNTs.** `promotion-service/.../repository/CouponUsageReservationRepository.java:24-31`:
```java
long countByCampaignIdAndUserIdAndStatusIn(UUID campaignId, UUID userId, Collection<UsageStatus> statuses);

long countByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);

@Query("select coalesce(sum(r.discountAmount), 0) from CouponUsageReservation r " +
       "where r.campaignId = :campaignId and r.status in :statuses")
BigDecimal sumDiscountByCampaignIdAndStatusIn(@Param("campaignId") UUID campaignId,
                                              @Param("statuses") Collection<UsageStatus> statuses);
```

These are **read** queries. The write happens later, at `CouponUsageReservationRepository.java` (the `save` of a new `CouponUsageReservation` row at `CampaignReservationServiceImpl.java:124`). **There is no `UPDATE … SET used_count = used_count + 1 WHERE used_count < limit`. There is no counter column. There is no `SELECT … FOR UPDATE` on the campaign.**

**The only DB-level uniqueness is per-ORDER, not per-user/per-coupon:** `changelog-001-initial-schema.yaml:118-123` `uk_cur_order_id` — one reservation per `order_id`. That is **idempotency**, not quota enforcement.

**Concurrency safety comes from OPTIMISTIC LOCKING, not from an atomic counter.** `promotion-service/.../entity/Campaign.java:58-61`:
```java
@Version
@Column(nullable = false)
private Long version = 0L;
```
and `CampaignReservationServiceImpl.java:105-110`:
```java
// ⚠️ Race protection (spec §5.2) — explicit choice: version-touch on the campaign row.
// two concurrent reserves can both pass. Touching a mapped field + saveAndFlush makes
// OptimisticLockingFailureException → the retry wrapper re-reads (now seeing the
// winner's committed PENDING row) and re-validates → correctly rejected.
campaign.setUpdatedAt(now);
campaignRepository.saveAndFlush(campaign);
```

The retry wrapper is `promotion-service/.../service/impls/ReservationRetryServiceImpl.java:72-88` — 3 attempts, linear backoff `50 ms × attempt`, **only** `OptimisticLockingFailureException` is retried; exhaustion → `PRO-7011`.

### Concurrent redemption of the last coupon — analysis

**Scenario:** a campaign with `per_user_limit = 1`, one user, two concurrent reserve requests for different orders.

1. T1 and T2 both enter `reserve()`. Both read the campaign (version = v).
2. Both read the quota: `countByCampaignIdAndUserIdAndStatusIn(...)` → **0** for both. Both pass the PER_USER gate. **The window between the COUNT and the INSERT is open.**
3. Both compute the discount, both set `campaign.setUpdatedAt(now)`, both `saveAndFlush(campaign)`.
4. One of them commits the campaign UPDATE (version v → v+1); the other's UPDATE affects 0 rows → `OptimisticLockingFailureException`.
5. The loser's `withRetry` catches it, sleeps, re-reads the campaign (now version v+1), re-runs `reserve()`. On the retry the COUNT now sees the winner's committed `PENDING` row → **1 >= 1** → `PER_USER_LIMIT_EXCEEDED` (PRO-7006). ✅

**Verdict: the limit IS enforced, but by optimistic concurrency + retry, not by an atomic counter.** The `Campaign.@Version` write is the serialization point, and it *does* serialise the whole reserve flow (the version-touch happens **before** the reservation insert, in the same transaction, so the loser's transaction aborts entirely and retries from scratch).

**Why this is nevertheless fragile:**
- **The retry budget is 3 attempts with a 50 ms linear backoff.** Under high contention on a *popular* campaign, 3 attempts can be exhausted → `PRO-7011` (a 500-class error) for a request that is merely "come back later". Under 8-way contention, `ReserveFlowIT.java:274-327` shows 5 of 8 attempts ending in `PRO-7006`/`PRO-7011` — i.e. **the retry budget is the binding constraint, not the limit**.
- **The version-touch is an implicit serialisation of an unrelated write.** `campaign.setUpdatedAt(now)` mutates the campaign's audit timestamp on *every* reserve, even one that changes nothing. Every reserve is a write to the campaign row ⇒ a hot row ⇒ lock contention and write amplification on the most valuable row of the table. (It is a deliberate choice, documented in the source; `AbstractMappedEntity.setUpdatedAt` exists precisely to support it — `utils/common-core/.../AbstractMappedEntity.java`.)
- **The version column is hand-bumped in a JPQL string** for the expiry claim (`CouponUsageReservationRepository.java:69-70`: `SET r.status = :expired, r.releasedAt = :now, r.version = r.version + 1`). Two places own the version semantics; they can drift.
- **The counters are not "per coupon"** — they are per *campaign*. `per_user_limit` and `max_redemptions` are both campaign-level attributes (`entity/Campaign.java:45-52`), so "per-coupon usage limit" is modelled as "per-campaign, optionally per-user". There is **no `Coupon` entity at all** in promotion-service — a "coupon" is a campaign identified by `code` (`entity/Campaign.java:24` `code`), and the unique index is `uk_campaign_code_live` (`changelog-001-initial-schema.yaml:55-56`).

### Why the atomic counter would be better

An atomic `UPDATE campaigns SET used_count = used_count + 1 WHERE id = ? AND (used_count < max_redemptions) OR (per_user_used < per_user_limit)` — with the affected-row count as the gate — makes the limit a **single-statement, database-enforced** invariant that no amount of concurrency can violate, with no retry loop, no hot-row version-touch, and no 3-attempt ceiling. The current design achieves the same *outcome* but pays for it with a retry loop, a hot row and a failure mode (PRO-7011) that is indistinguishable from a genuine server error.

**Recommendation (CAT-07):** keep `Campaign.@Version` for the general optimistic-locking guarantee, but replace the three COUNT-based gates with a single atomic conditional UPDATE on a counter set (`per_user_used`, `used_count`, `budget_used`) whose affected-row count is the gate. The version-touch at `:109-110` then disappears.

## 6.3 Expiry — **enforced server-side** ✅

- **TTL is server-side.** `CampaignReservationServiceImpl.java:45-46` `@Value("${shop.promotion.reservation-ttl-seconds:900}")`; `:119` `expiresAt = now + TTL`. The client cannot influence it.
- **Expiry is enforced at commit time.** `CampaignReservationServiceImpl.java:155-157`:
  ```java
  if (!reservation.getExpiresAt().isAfter(now)) {
      throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_EXPIRED, id);
  }
  ```
- **The clock is injected.** `CampaignReservationServiceImpl.java:43` `private final Clock clock;` and `:59` `Instant now = clock.instant();` — testable, not `Instant.now()`. ✅
- **Expiry of the campaign window is server-side.** `:61-65` compares `clock.instant()` against `startsAt`/`endsAt`.
- **The TTL sweep is a conditional atomic UPDATE (credit — this fixes a real lost-update).** `CouponUsageReservationRepository.java:53-74`:
  ```java
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE CouponUsageReservation r SET r.status = :expired, r.releasedAt = :now, r.version = r.version + 1 "
       + "WHERE r.id = :id AND r.status = :pending")
  int claimForExpiry(...)
  ```
  The javadoc at `:53-67` records the original bug: "The sweep used to SELECT the candidates and then dirty-check them to EXPIRED. Without a version column, a sweep that read the row before `commit()` ran could overwrite the COMMITTED state — which stops the redemption from being counted against the per-user limit and the campaign budget." Only the writer whose UPDATE matches `status = PENDING` transitions the row; every other writer affects zero rows and does nothing. ✅
- **The reservation row has its own `@Version`.** `entity/CouponUsageReservation.java:73-76` — added by `changelog-004-coupon-reservation-version.yaml` (author `production-audit`).
- **Quota is returned on expiry (proven).** `ReserveFlowIT.java:332-373` `budgetQuotaReturnedWhenReservationExpires` — 60 of 100 reserved → a second reserve is refused with PRO-7007 → the hold is flipped to `EXPIRED` → the same 60 fits again.

## 6.4 Reservation lifecycle (state machine) — correct ✅

`CampaignReservationServiceImpl.java`:
- `commit` (`:142-163`) — COMMITTED is idempotent (`:147-150`); RELEASED/EXPIRED → `PROMOTION_RESERVATION_INVALID_STATE` (PRO-7010, fail-closed) (`:151-154`); expired PENDING → PRO-7009 (`:155-157`); publishes `promotion.committed.v1`.
- `release` (`:171-188`) — PENDING → RELEASED, publishes `promotion.released.v1` with `previousStatus="PENDING"`.
- `releaseCommitted` (`:196-213`) — the half-commit compensation path, publishes `previousStatus="COMMITTED"` so consumers can distinguish it.
- `getState` (`:216-222`) — a read-only projection for reconciliation polling.

The `previousStatus` field in the released event is a genuinely good design touch: the consumer can tell a plain reserve→release from a commit→rollback compensation.

## 6.5 Caller authentication (credit)

`promotion-service/.../controller/PromotionReservationController.java:39,47,56,65` — every mutating endpoint calls `serviceCallerGuard.requireCaller("order-service")`. The read-only state endpoint (`:70-74`) intentionally has no caller guard. A coupon reservation is a machine-to-machine operation; allowing a browser token to mint a reservation would be a business-logic bypass.

## 6.6 Outbox

`promotion-service/.../service/impls/TransactionalPromotionEventPublisher.java` writes the outbox row in the same transaction as the reservation lifecycle change. Event types are dotted-cased (`promotion.reserved.v1`, `promotion.committed.v1`, `promotion.released.v1`) — a **different convention** from product/media/rating, which use PascalCase (`ProductCreated`, `MediaCreated`, `rating.submitted.v1` is dotted — so rating and promotion agree, product and media do not). Cosmetic, but it means the fleet has no single event-naming convention (CAT-18, informational).

---

---

# 7. RATING SERVICE — verified buyer, duplicate reviews, aggregate recalculation, claim lease

## 7.1 Verified-buyer check — **proven by a remote, fail-closed call** ✅

`rating-service/.../eligibility/EligibilityClient.java:50-69` — the check is a call to order-service:
`GET /api/v1/orders/verify-purchase?userId=&productId=`

It returns `true` **only** if order-service answers with a non-empty page of **DELIVERED** order items for that (user, product). Any `RestClientException` → `false` (fail-closed). A `@CircuitBreaker(name="orderService")` wraps the call.

**How "verified" is proven:** not by a local flag, not by a JWT claim, not by a time window — by asking the *owning* context (order-service) whether a delivered order exists for this user+product. That is the correct derivation of the invariant: only order-service knows whether the purchase happened. rating-service stores the outcome as `Rating.verified` (`entity/Rating.java:47` `private boolean verified = false;`) as a **snapshot of a decision made elsewhere**, not as an authority.

**Fail-closed is the right polarity** (credit): a degraded order-service means "nobody may review", not "everybody may review".

**The check runs OUTSIDE the transaction (credit — F-066).** `rating-service/.../service/impls/RatingServiceImpl.java:36-53`:
```java
public RatingResponse submit(UUID jwtUserId, RatingSubmitRequest request) {
    // F-066: the eligibility check is a REMOTE call. It used to run inside
    // the @Transactional method, so a slow order-service held a pooled DB
    // connection and an open transaction for the whole round trip (3-5s
    // timeouts) - a burst against a degraded order-service exhausts the
    // pool and stalls unrelated requests. The check now runs before the
    // transaction is opened; only the local write is transactional.
    ratingRepository.findByUserIdAndProductIdAndDeletedFalse(jwtUserId, request.productId())
            .ifPresent(existing -> { throw BusinessException.of(ErrorCode.RATING_ALREADY_EXISTS); });

    boolean eligible = eligibilityClient.isEligible(jwtUserId, request.productId());
    if (!eligible) { throw BusinessException.of(ErrorCode.RATING_NOT_ELIGIBLE); }

    RatingResponse response = transactionTemplate.execute(status -> { ... });
```

`RatingServiceImpl.java:54` uses a `TransactionTemplate` (injected at `:34`) rather than `@Transactional`, precisely so the remote call can sit outside the transaction boundary. **This is the same pattern product-service uses for its media HEAD check, and it is proven the same way** — `src/test/.../service/impls/RatingServiceImplTransactionBoundaryIT.java` asserts the transaction is not active during the eligibility call.

## 7.2 Duplicate review prevention — **ENFORCED AT THE DATABASE LEVEL** ✅

`rating-service/src/main/resources/db/changelog/changelog-001-ratings.yaml:42`:
```sql
CREATE UNIQUE INDEX uk_rating_user_product_live ON ratings (user_id, product_id) WHERE deleted = false;
```

A **partial unique index** — one live rating per (user, product), and a soft-deleted row does not block a new one. This is the correct DDD/explicit-architecture answer: the invariant is enforced by the database, not by a Java pre-check.

**Second line of defence (credit):** `RatingServiceImpl.java:44-47` does a friendly pre-check (`findByUserIdAndProductIdAndDeletedFalse`) so the common case returns a clean `RATING_ALREADY_EXISTS` (RTG-11005) instead of a raw constraint violation. The database remains the authority.

**Also pinned in the changelog:** `:39-41` — CHECK constraints on the rating value (1..5) and the comment length (5..2000), plus the audit trio.

**Contrast with favourite-service** (`changelog-001:36-39` `idx_favourites_user_product_unique_active`) — same pattern, same partial-index idiom. **The fleet is consistent here.** ✅

## 7.3 Concurrent review updates and aggregate rating recalculation

### The recalculation itself is NOT a racy read-modify-write ✅

`rating-service/.../service/RatingEventService.java:54-58`:
```java
@Transactional(propagation = Propagation.REQUIRED)
public void record(Rating rating, RatingAction action) {
    Object[] aggregate = ratingRepository.findAggregateByProductId(rating.getProductId()).get(0);
    BigDecimal avgRating = toBigDecimal(aggregate[0]).setScale(2, RoundingMode.HALF_UP);
    int ratingCount = ((Number) aggregate[1]).intValue();
```

`repository/RatingRepository.java:22-24` `findAggregateByProductId` is a **JPQL aggregate query** (`avg(rating)`, `count(r)`) evaluated **inside the caller's transaction**. There is **no stored counter column** being read-modify-written — the average is computed live from the rows. Two concurrent reviews therefore cannot lose an increment: each computes its own snapshot from a consistent view.

**The flush discipline is correct and explicitly documented.** `RatingServiceImpl.java:64-67`:
```java
// NIT #1: flush REQUIRED before record() — the snapshot aggregate JPQL
// only sees this row once it is flushed in the current transaction.
Rating saved = ratingRepository.saveAndFlush(rating);
ratingEventService.record(saved, RatingAction.CREATED);
```
and `RatingEventService.java:47-53`:
> "Joins the caller's transaction (REQUIRED): the snapshot aggregate must see the caller's new/updated row, which is why every caller MUST `saveAndFlush` before calling this (plan nit #1) — a plain `save` leaves the row unflushed and the JPQL aggregate would compute over stale data."

The same discipline is applied in `edit` (`:94-96`), `hide` (`:115-117`) and `unhide` (`:133-135`). ✅

### BUT the snapshot in the event can be stale (CAT-19)

`RatingEventService.java:56-58` computes the aggregate **at the moment `record()` runs**. Under `READ COMMITTED` (Postgres's default, and Spring's default), two concurrent reviews for the *same product* each see only *their own* uncommitted row plus the other's committed-or-not state:

- T1 (rating 5) flushes, computes avg over {5} → avg=5.00, count=1.
- T2 (rating 1) flushes, computes avg over {1} → avg=1.00, count=1.   ← **T2 does not see T1's uncommitted row**
- Both write their outbox rows.

The last writer's snapshot (`avg=1.00, count=1`) is **wrong** — the true aggregate is avg=3.00, count=2.

**Consequence:** the outbox payload carries a *stale aggregate*, and product-service copies it **verbatim** onto the product row (`product-service/.../service/ProductRatingService.java:25-38`, and the test `ProductRatingServiceTest.java:119-130` explicitly pins "copied verbatim, no action filtering"). So `products.avg_rating` / `products.rating_count` can permanently display `1.00 / 1` when the truth is `3.00 / 2` — and it will be corrected only if *another* rating event for that product happens to arrive later.

**Severity:** medium. The storefront rating display is wrong until the next rating. The fix is small: take a row lock on the product's ratings (`SELECT … FOR UPDATE` on the newest rating row, or a dedicated `product_rating_stats` row with `@Version`) before computing the aggregate, so the two transactions serialise on the snapshot computation.

Note: the *ratings themselves* are never lost — the live aggregate query always returns the truth. Only the **snapshot event** (and hence the denormalised copy on the product row) is affected.

## 7.4 Outbox claim lease — **VERIFIED** ✅

`rating-service/src/main/resources/db/changelog/changelog-002-rating-outbox-claim-lease.yaml` adds a `claimed_at` column plus an index.

`rating-service/.../outbox/OutboxEvent.java:53-54`:
```java
@Column(name = "claimed_at")
private Instant claimedAt;
```

`rating-service/.../outbox/OutboxEventRepository.java:74-85` `reclaimStaleSending` — a conditional bulk UPDATE that uses `e.claimedAt` (not `updated_at`) as the lease clock:
```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE OutboxEvent e SET "
     + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
     + "e.retryCount = e.retryCount + 1, "
     + "e.lastError = :reason "
     + "WHERE e.status = :sending AND e.claimedAt < :cutoff")
```

The lease duration is configurable: `outbox/RatingOutboxRelay.java:79-80` `@Value("${shop.rating.outbox.claim-lease-ms:60000}") claimLeaseMs`.

**The claim itself is a pessimistic, SKIP-LOCKED claim** — proven by `src/test/.../outbox/RatingOutboxClaimConcurrencyIT.java` (mirrors the product/media equivalents): the loser of a concurrent claim gets `Optional.empty()`, never the same row.

**Recovery is proven to be part of the RELAY, not a stray repository method:** `src/test/.../outbox/RatingOutboxRelayRecoveryIT.java` drives `RatingOutboxRelay.relay()` and asserts a stranded `SENDING` row past its lease is re-published and ends `SENT`, while a **fresh** `SENDING` row is left alone (its publish may still be in flight).

**Rating-service weakness (CAT-10, shared with product-service):** `rating-service/.../outbox/OutboxEvent.java` has `claimed_at` but **no `failed_at`**, and `rating-service/.../outbox/OutboxRetentionScheduler.java:30-42` purges **only** `SENT` rows. Therefore a `FAILED` rating event is **terminal** — it is never replayed and never aged to `DEAD`. media-service has the full ladder (`outbox/OutboxEvent.java:49` `failedAt`; `outbox/MediaOutboxRetentionScheduler.java:26-46` ages `FAILED`→`DEAD`; `MediaOutboxIT.java:285-306` proves a `FAILED` row is replayed and then aged). rating-service (and product-service) lack that recovery path: a transient Kafka outage that exhausts the retry budget **silently drops the event**.

## 7.5 Other rating-service observations

- `RatingServiceImpl.java:87` — `edit` looks the rating up by `(userId, productId)` from the JWT subject, so a user can only edit their own rating. **Ownership is derived from the JWT, not from a request parameter.** ✅
- `RatingServiceImpl.java:102-119` `hide` and `:122-137` `unhide` — admin moderation, each publishing its own lifecycle event with the aggregate snapshot. `hide` sets `hiddenAt`/`hiddenBy`/`hiddenReason`.
- `RatingServiceImpl.java:74-82` `findVisibleByProductId` — `findByProductIdAndHiddenFalseAndDeletedFalse` with a bounded page size (`PageableConstant.MAX_PAGE_SIZE`, `:77`).
- `Rating.verified` is stored but the **storefront read path filters on `hidden`/`deleted` only** (`:80`) — a `verified=false` rating (from a pre-verification era or a migration) is still displayed. The `verified` flag is therefore carried but never enforced at read time. Informational (CAT-20, low).

---

---

# 8. TAX SERVICE — rule evaluation, rounding, pricing contract

## 8.1 Rule evaluation — tiered resolution, specific-first ✅

`tax-service/.../service/impls/TaxCalculationServiceImpl.java:34-40` resolves the rate in this order:
1. **postal-code-specific** rate for (tax_class, country, postalCode)
2. **country-wide** rate for (tax_class, country, postalCode = null)
3. **class default** (`TaxClass.defaultRatePct`)
4. otherwise → `TAX-8002`

`tax-service/.../repository/TaxRateRepository.java:18-23` — the query orders specific-before-generic:
```java
order by case when r.postalCode is null then 1 else 0 end
```
so the postal-specific row wins when both exist.

**Subtle correctness detail (credit):** the JPQL predicate is `r.postalCode = :postalCode`, and in SQL `= NULL` is never true. A `null` `:postalCode` therefore **never matches** the country-wide row through that predicate — the country-wide row is reached through the separate `is null` branch of the resolution. The design is correct because the two cases are resolved by distinct code paths, not by relying on `= NULL` semantics.

`tax-service/.../dto/request/TaxCalculateRequest.java:12` — country is validated `^[A-Z]{2}$` at the edge.

## 8.2 Rounding — fixed scale 2, HALF_UP ✅ (with one caveat)

`tax-service/.../service/TaxCalculator.java:13-16`:
```java
public static TaxCalculateResponse calculate(BigDecimal amount, BigDecimal ratePct) {
    BigDecimal taxAmount = amount.multiply(ratePct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    return new TaxCalculateResponse(taxAmount, ratePct);
}
```
- `divide(…, 2, RoundingMode.HALF_UP)` — an **explicit scale and rounding mode** on the divide, so there is no `ArithmeticException` for non-terminating decimals and no scale drift. ✅
- `BigDecimal.multiply` preserves the exact product; only the final divide rounds. ✅
- The rate is a percentage: `amount × ratePct / 100`.

**Caveat (CAT-21, low):** HALF_UP is used for **tax**, which in many jurisdictions must be HALF_EVEN (banker's rounding) or must round *up* (a common VAT rule: "round up to the next cent"). HALF_UP is a defensible default but it is a **business rule that is not documented anywhere in the service** and not configurable. The `TaxCalculatorTest` pins the current behaviour, so changing it is a deliberate act — but the absence of any statement of *which* rounding regime the platform promises is a contract gap.

**Caveat (CAT-22, low):** there is **no currency** on the tax result (same fleet-wide gap as CAT-05) and **no tax-inclusive / tax-exclusive flag**. The response carries the tax amount and the rate, and the caller must know whether the price it sent was gross or net.

## 8.3 Pricing contract with order-service

**There is no explicit pricing contract.** The coupling is a single endpoint:
`tax-service/.../controller/TaxCalculationController.java:30` `serviceCallerGuard.requireCaller("order-service")` — only order-service may call the calculation endpoint.

The request DTO (`dto/request/TaxCalculateRequest.java`) carries: amount, tax class id, country, postal code. The response carries the computed tax amount and the applied rate.

**What is missing from a *contract*:**
- **No currency** on either side (CAT-05) — order-service cannot know whether the amount it sent is USD or VND.
- **No net/gross declaration** (CAT-22).
- **No rounding-regime declaration** (CAT-21).
- **No version/negotiation** on the wire format — a breaking change to the DTO silently breaks order-service.
- **No idempotency key** — a retried calculation returns a fresh result (harmless today because the calculation is pure, but the contract does not say so).

**Credit:** the caller guard means the tax service is *not* publicly callable, and the calculation is a **pure static function** (`TaxCalculator.java:8-11` — private constructor, no state, no injected collaborators). A pure function with an explicit rounding mode is the right shape for a pricing calculation and is trivially unit-testable (`TaxCalculatorTest`).

## 8.4 Tax catalogue management

`tax-service/.../service/TaxClassService.java` and `TaxRateService.java` are thin CRUD ports (create/update/get/list/delete). The implementations (`service/impls/TaxClassServiceImpl.java`, `TaxRateServiceImpl.java`) do the work. `TaxRateServiceImpl.java:87` `saveHandlingDuplicate`, `:95` `requireTaxClass`, `:100` `requireNoDuplicate`, `:106` `normalize(postalCode)`.

Both `TaxClass` (`entity/TaxClass.java:33`) and `TaxRate` (`entity/TaxRate.java:42`) carry `@Version` → optimistic locking on catalogue edits. ✅

**DB invariants:** `changelog-001-initial-schema.yaml:70` — a unique live index on `(tax_class_id, country, COALESCE(postal_code,'')) WHERE deleted = false`; a CHECK constraining the rate to 0..100.

---

---

# 9. FAVOURITE / NOTIFICATION — isolation, duplicates, bounded retry

## 9.1 favourite-service — user isolation ✅ and duplicate handling ✅

**User isolation is enforced in SQL, not in Java.** `favourite-service/.../repository/FavouriteRepository.java`:
- `:28` `findByIdAndUserId(...)` — a lookup is owner-scoped; there is no `findById` in the service path.
- `:38-50` `softDeleteByIdAndUserId(...)` — an **atomic, owner-scoped UPDATE**. A user cannot delete someone else's favourite even by guessing the id, because the `WHERE` clause includes the owner.
- `:56-68` `softDeleteByUserIdAndProductId(...)` — same, keyed by (owner, product).

`favourite-service/.../service/impls/FavouriteServiceImpl.java:87` `findOwnedOrThrow(UUID id, UUID userId)` — a missing *or* not-owned row raises the same error, so the API does not leak the existence of another user's favourite. ✅

**The user id comes from the JWT, never from the request body.** `favourite-service/.../controller/FavouriteController.java:65-73` `currentUserId()` reads the JWT `sub`; a malformed subject → 401.

**Duplicate handling is race-safe.** `FavouriteServiceImpl.java:50-64`:
- a pre-check (`existsByUserIdAndProductId`) for a friendly error, **and**
- a `DataIntegrityViolationException` catch around the insert.

The database is the authority: `changelog-001:36-39` `idx_favourites_user_product_unique_active` — a partial unique index on (user, product) where the row is live. The Java pre-check is only for UX; the index is what makes the race safe. **This is the same "pre-check for UX + DB index for correctness" idiom as rating-service.** ✅

## 9.2 notification-service — duplicate handling ✅

`notification-service/.../service/impls/NotificationServiceImpl.java:56-95` `handle(OrderLifecycleEvent)`:
- `:57-60` parse the event id at the trust boundary; a missing/blank/invalid id → log + skip (poison messages are acked, not retried).
- `:61-64` `repository.existsByEventId(eventId)` → skip (duplicate).
- `:80-85` `writer.insert(notification)` inside a `try { } catch (DataIntegrityViolationException e)` → a concurrent consumer that wins the insert is skipped, not failed.

The authority is the unique index `uk_notification_event_id` on `notifications.event_id`. ✅ Same idiom.

## 9.3 Notification retry — **durable and bounded, no infinite loop** ✅

### Bounded attempts

`notification-service/.../service/NotificationRetryPolicy.java`:
```java
private static final int BACKOFF_MULTIPLIER = 5;
private static final int MAX_BACKOFF_EXPONENT = 10;

public NotificationRetryPolicy(
        @Value("${shop.notification.retry.max-attempts:6}") int maxAttempts,
        @Value("${shop.notification.retry.backoff-base-seconds:300}") long baseBackoffSeconds) { … }

public boolean isExhausted(int retryCount) { return retryCount >= maxAttempts; }

long backoffSeconds(int attempt) {
    long mult = 1;
    for (int i = 1; i < attempt && i <= MAX_BACKOFF_EXPONENT; i++) { mult *= BACKOFF_MULTIPLIER; }
    return baseBackoffSeconds * mult;
}
```

- **Bounded attempt count:** `max-attempts: 6` → the 6th failure is terminal.
- **Exponential backoff** with the fleet convention `base × 5^(attempt-1)`, `base = 300 s`.
- **Overflow-safe:** `MAX_BACKOFF_EXPONENT = 10` caps the multiplier at `5^10 = 9,765,625`, so `300 s × 9,765,625 ≈ 34 years` — a legacy row with a huge `retry_count` cannot overflow the `long` or produce a negative delay. The comment at `:19` says exactly this. ✅
- `5^9 × 300 s ≈ 10.6 days` — the effective worst-case backoff, comfortably inside the retention window.

### Durable state (survives a restart)

`changelog-002-notification-retry.yaml` adds `retry_count`, `next_retry_at` and `last_error` columns to `notifications`, widens the status CHECK to the 7 states, and **backfills** pre-existing `FAILED` rows to `FAILED_RETRYABLE` so no notification is silently dropped by the migration. `changelog-003-retry-index.yaml` adds the `(status, next_retry_at)` index that the candidate query uses.

**The retry state lives in the database, not in memory** — a notification-service restart does not lose the retry schedule. ✅

### Claim-then-send with a heartbeat (credit)

`notification-service/.../scheduler/NotificationRetryScheduler.java:67-88`:
```java
@Scheduled(fixedDelayString = "${shop.notification.retry.poll-ms:60000}",
           initialDelayString = …)
```
- `:96-111` `claim(Instant now)` — a **pessimistic claim** (`repository/NotificationRepository.java:38-48` `findRetryCandidates` with `@Lock(PESSIMISTIC_WRITE)`) performed in **one short transaction**, which flips the row to `SENDING` and stamps a **heartbeat deadline** (`now + shop.notification.retry.stale-sending-seconds`, default 900 — `NotificationServiceImpl.java:115-117`).
- The **send happens OUTSIDE any transaction** — no DB connection or open transaction is held across the SMTP round trip. ✅
- A worker that dies mid-send leaves a `SENDING` row whose heartbeat expires → another cycle reclaims it. No lost work, no double-send beyond the at-least-once window.

### Permanent-vs-retryable classification

`notification-service/.../service/sender/NotificationFailureClassifier.java:24-37` — walks the exception cause chain (bounded to depth 16) and classifies `AddressException` / "invalid addresses" as **PERMANENT** (a malformed address will never succeed); everything else is retryable.

`notification-service/.../service/NotificationDeliveryService.java:39-64` — `deliver` → `markSent` / `markRetryable` / `markPermanent`.

`notification-service/.../service/NotificationWriter.java:39-104` — every state transition is guarded by the row's current status, so two concurrent workers cannot both advance the same row.

`notification-service/.../service/sender/NotificationSenderConfig.java:13-20` — the sender is chosen by channel with a `@Primary` bean; SMTP when configured, otherwise a LOG sender (so a misconfigured SMTP does not crash the context).

### The one gap (CAT-23, low)

`NotificationRetryPolicy.isExhausted(retryCount >= maxAttempts)` is consulted *before* a send. A row that exhausts its budget is moved to a permanent-failure status and is **never retried** — correct. But there is **no retention/purge scheduler for notification rows** anywhere in the service (the only `@Scheduled` in notification-service is the retry poller, `NotificationRetryScheduler.java:67`). Terminal rows (`FAILED_PERMANENT`, `SENT`) therefore accumulate indefinitely. The `notifications` table grows by one row per order lifecycle event, forever. Compare: every event-producing service in scope *does* purge its outbox (`product-service/.../service/OutboxRetentionScheduler.java:32`, `media-service/.../outbox/MediaOutboxRetentionScheduler.java:26`, `promotion-service/.../service/OutboxRetentionScheduler.java:26`, `rating-service/.../outbox/OutboxRetentionScheduler.java:30`) — notification-service has no equivalent for its own domain table. **This is the same class of finding the outbox-retention fixes documented in each of those javadocs.**

---

---

# 10. SINGLETON & THREAD-SAFETY ACROSS THE EIGHT SERVICES

Every Spring bean in scope is a **singleton** by default. This section enumerates **every** mutable instance/static field, shared collection, cached state, `@Scheduled`, `@Async`, non-thread-safe collaborator and check-then-act in the eight services.

## 10.1 Exhaustive mutable-field census

A mechanical sweep of every `src/main/java` file in the eight services for non-`final`, non-`static` instance fields produced exactly **one** field that is a genuine mutable-state defect on a singleton bean:

| Service | File:line | Field | Verdict |
|---|---|---|---|
| **product** | `job/ProductMediaSweepJob.java:27` | `private int currentPage = 0;` | **DEFECT — CAT-03.** See §10.2. |

Every other non-`final` instance field is one of:
- a **JPA entity field** (`entity/*.java` — `Product.java:24-77`, `Media.java:44-62`, `Campaign.java:21-66`, `Rating.java:30-63`, `Notification.java:41-83`, `TaxRate.java:23-42`, `Favourite.java:32-38`, `CouponUsageReservation.java:40-76`, …). Entity fields are **not** singleton state — each entity instance is created per request/row and is managed by the persistence context. Not a thread-safety concern.
- a **`@Value`-injected configuration field**, set once by the container at construction and thereafter read-only. Listed exhaustively below.
- a **method or record**, matched by the sweep regex but not a field.

### `@Value`-injected configuration fields (set once at construction, read-only thereafter — safe, but not `final`)

| Service | File:line | Field |
|---|---|---|
| product | `service/OutboxRelay.java:76,79,82,90` | `batchSize`, `maxRetries`, `publishTimeoutMs`, `claimLeaseMs` |
| media | `outbox/MediaOutboxRelay.java:77,80,83,91` | same four |
| media | `outbox/MediaOutboxRetentionScheduler.java:24` | `retentionDays` |
| promotion | `service/PromotionOutboxRelay.java:72,75,78,86` | same four |
| promotion | `service/ReservationCleanupScheduler.java:44,47` | `batchSize`, `retentionDays` |
| promotion | `service/impls/CampaignReservationServiceImpl.java:46` | `reservationTtlSeconds` |
| rating | `outbox/RatingOutboxRelay.java:66,69,72,80` | same four |
| notification | `service/impls/NotificationServiceImpl.java:38` | `claimStaleSeconds` (constructor-injected) |

**Assessment:** these are safe under Spring's singleton publication semantics (the container fully constructs the bean before publishing it). They are nevertheless a **code smell**: making them `final` via constructor injection would make the immutability structural rather than conventional. Recommended as a **non-blocking** cleanup, not a defect.

## 10.2 The one real defect: `ProductMediaSweepJob.currentPage` (CAT-03)

`product-service/.../job/ProductMediaSweepJob.java`:
```java
20: public class ProductMediaSweepJob {
…
27:     private int currentPage = 0;
28:
29:     @Scheduled(cron = "${shop.product.media-sweep.cron:0 */30 * * * *}")
30:     public void sweep() {
…
37:             var page = productRepository.findByMediaIdIsNotNull(
38:                 PageRequest.of(currentPage, properties.limit()));
39:             if (page.isEmpty() || !page.hasNext()) {
40:                 currentPage = 0;
41:             } else {
42:                 currentPage++;
43:             }
```

**Three distinct problems:**

1. **Data race.** `currentPage` is a plain `int` on a singleton bean. It is read at `:38` and written at `:40`/`:42` with **no `volatile`, no `synchronized`, no `AtomicInteger`, and no `@PreDestroy`/reset**. A read-modify-write (`currentPage++`) is not atomic: two concurrent readers of the same value both compute `currentPage + 1` and one increment is lost. There is no `volatile` so the write may never become visible to another thread at all (JMM §17.4). A reader can also observe a **torn** stale value and re-read the same page indefinitely.
2. **Concurrent execution is possible.** Spring Boot's default `TaskScheduler` pool size is **1**, so with the default configuration only one sweep runs at a time — the race is latent, not active. But `spring.task.scheduling.pool.size` is a standard override, and the sweep takes a **remote HTTP HEAD per row** (`MediaHeadClient.exists`, `ProductMediaSweepJob.java:48`) — with `limit` rows per cycle that is a *long* task. With pool size ≥ 2 the two sweeps overlap and the race becomes real.
3. **Pagination drift / skipped rows.** The page cursor is *state on the bean*, not derived from a stable sort. `findByMediaIdIsNotNull(Pageable)` (`ProductRepository`) is **not** guaranteed to be ordered — there is no `ORDER BY` in the derived query, and the row set changes between cycles (references get cleared). Rows can therefore be **skipped** (a row moves to a later page while the cursor advances) or **visited twice**. The sweep is a best-effort reconciliation, so a skip is not catastrophic — but it means the sweep does not guarantee full coverage, and the `currentPage` state makes the coverage *unpredictable*.

**Proof the rest of the job is correct:**
- Exactly one page per cycle: `ProductMediaSweepJobTest.java:96-111` `paging_exactlyOnePageOfLimit_noPaginationLoop` asserts `findByMediaIdIsNotNull` is called **once** with `PageRequest.of(0, 100)`.
- Fail-safe on outage: `ProductMediaSweepJobTest.java:148-166` `outage_abortsCycle_failSafe` — a fail-closed `MEDIA_STORAGE_UNAVAILABLE` from the client aborts the **entire** cycle: only 1 row checked, 0 cleared, and `productMediaService` never touched. `ProductMediaSweepJobIT.java:155-173` proves the same over real HTTP with a connection-reset fault: the reference is untouched and **no** `ProductUpdated` is re-published.
- Clear-failure also aborts the cycle: `ProductMediaSweepJobTest.java:168-182`.
- A dead media shared by several rows is cleared for every row: `ProductMediaSweepJobIT.java:119-153` — two dangling products sharing one dead media → both cleared, **2** `ProductUpdated` outbox rows, and the `cleared` meter counts **rows** (2), not calls (which would be 4 because the second call is a no-op replay).

**Fix:** delete the field entirely and always query `PageRequest.of(0, properties.limit())` — one page per cycle, starting from the beginning. The pagination cursor buys nothing (the sweep is a periodic reconciliation of *all* media-referencing products) and is the source of the race. If a cursor is genuinely wanted, it must be `AtomicInteger` and reset per cycle.

## 10.3 Static state

**No mutable static fields in production code.** A sweep for `static` fields that are not `final` found none in `src/main/java` across the eight services. The only mutable statics are in **test** code (e.g. `UploadIT.java:419-422` `static final List<String> writtenKeys = Collections.synchronizedList(...)`, `static volatile int failAfter`) — correctly guarded and confined to tests.

`static final` constants are immutable and safe:
- `media-service/.../service/impls/MediaFormats.java:13-18` — `ALLOWED_CONTENT_TYPES` (immutable `Set.of`), `CONTENT_TYPES`/`EXTENSIONS` (immutable `Map.of`).
- `product-service/.../service/impls/TransactionalProductEventPublisher.java:38-39` — `AGGREGATE_TYPE_PRODUCT`, `LIFECYCLE_TOPIC`.
- `promotion-service/.../service/impls/CampaignReservationServiceImpl.java:37-38` — `COUNTED_STATUSES` (immutable `List.of`).
- `media-service/.../service/impls/MediaUploadServiceImpl.java:43-44` — `SNIFF_LENGTH = 32`, `SHA_256`.

**No `static` mutable collections, no `HashMap`/`ArrayList` instance fields on singletons** beyond the JPA entity collections (`Media.java:57` `private List<MediaVariant> variants = new ArrayList<>();`, `Category.java:43` `private Set<Category> children = new HashSet<>();`) — both are entity-scoped, managed by the persistence context, and replaced per request. ✅

## 10.4 Cached state

| Service | Location | Analysis |
|---|---|---|
| product | `config/CacheConfig.java:62-68` | Redis cache manager customiser with `immediateWrites()` (a `@CachePut` is visible to the next read) and per-cache TTLs (product 10 m, taxonomy 30 m). The cache is **external (Redis)** → no in-JVM shared mutable state, no visibility problem. ✅ |
| product | `ProductServiceImpl.java:252` `evictProductSlug(String slug)` | Evicts only the affected keys: `productCache.evict(ID)` and `slugCache.evict(slug)`. `ProductServiceImplTest.java:244-258` proves *only* those two are evicted. ✅ |
| product | `service/ProductMetrics.java:41` | `AtomicInteger pendingOutboxCount` — correct. ✅ |
| media | `metrics/MediaMetrics.java` | Micrometer counters (thread-safe by construction). ✅ |
| promotion | `service/PromotionMetrics.java:33` | `AtomicInteger`. ✅ |
| rating | `metrics/RatingMetrics.java` | Micrometer counters. ✅ |
| search | — | No cache. `SearchQueryServiceImpl` reads from ES on every request. |
| tax / favourite / notification | — | No cache. |

**Verdict: no in-JVM cache holds mutable state.** Every counter that is not a Micrometer meter is an `AtomicInteger`. ✅

## 10.5 `@Scheduled` — all 12 occurrences, exhaustively

| Service | File:line | Schedule | Thread-safety analysis |
|---|---|---|---|
| product | `job/ProductMediaSweepJob.java:29` | `cron 0 */30 * * * *` | **DEFECT — CAT-03.** Mutable `currentPage` on a singleton (§10.2). |
| product | `service/OutboxRelay.java:105` | `fixedDelay ${product.outbox.poll-interval-ms:5000}` | All state is in the DB; the relay holds **no** in-memory state. The DB claim (`FOR UPDATE SKIP LOCKED`) is the mutual-exclusion mechanism. ✅ |
| product | `service/OutboxRetentionScheduler.java:32` | `cron ${product.outbox.retention-cron:0 0 3 * * *}` | Stateless bulk delete. ✅ |
| search | *(none)* | — | search-service has **no** `@Scheduled` at all. |
| media | `outbox/MediaOutboxRelay.java:104` | `fixedDelay ${shop.media.outbox.poll-millis:2000}` | DB-backed claim. ✅ |
| media | `outbox/MediaOutboxRetentionScheduler.java:26` | `cron ${shop.media.outbox.retention-cron:0 0 3 * * *}` | Stateless. ✅ |
| media | `job/MediaPurgeJob.java:26` | `fixedDelay ${media.purge.poll-millis:3600000}` | Stateless between calls; per-item try/catch. ✅ |
| promotion | `service/PromotionOutboxRelay.java:101` | `@Scheduled(...)` | DB-backed claim. ✅ |
| promotion | `service/OutboxRetentionScheduler.java:26` | `cron ${promotion.outbox.retention-cron:0 0 3 * * *}` | Stateless. ✅ |
| promotion | `service/ReservationCleanupScheduler.java:69` | `fixedDelay ${shop.promotion.reservation-cleanup-interval-ms:60000}` | Batch sweep, one TX per batch, conditional-UPDATE claim per row. ✅ |
| promotion | `service/ReservationCleanupScheduler.java:117` | `cron ${shop.promotion.reservation-retention-cron:0 0 4 * * *}` | Stateless purge. ✅ |
| tax | *(none)* | — | tax-service has **no** `@Scheduled` at all — it is a pure request/response service with no local state to reconcile. Correct. |
| rating | `outbox/RatingOutboxRelay.java:93` | `fixedDelay ${shop.rating.outbox.poll-millis:2000}` | DB-backed claim. ✅ |
| rating | `outbox/OutboxRetentionScheduler.java:30` | `cron ${rating.outbox.retention-cron:0 0 3 * * *}` | Stateless. ✅ |
| favourite | *(none)* | — | Correct — no background state. |
| notification | `scheduler/NotificationRetryScheduler.java:67` | `fixedDelay ${shop.notification.retry.poll-ms:60000}` | DB-backed pessimistic claim + heartbeat. ✅ |

**No `@Async` anywhere in the eight services** (swept: zero hits). Every background execution is on the scheduler thread. This is a **deliberate and good choice**: `@Async` would have introduced an in-memory work queue with its own concurrency semantics, and every one of these jobs is a database-coordinated sweep that must be serialisable.

**No `@EnableScheduling` pool-size override was found in any `application.yml`** — the default pool size of 1 applies, which is why CAT-03's race is latent rather than active. That is a **mitigating factor, not a fix**: the default is a property, not a guarantee.

## 10.6 Non-thread-safe collaborators

**`java.util.Random`** — none used for anything but tests.

**`SimpleDateFormat`** — none; all date handling uses `java.time` (`Instant`, `Duration`, `Clock`). ✅

**`ObjectMapper`** (Jackson) — shared across threads, and Jackson's `ObjectMapper` is **thread-safe for reading and writing** once configured. The fleet uses `new ObjectMapper()` in tests and the Spring-managed bean in production. `TransactionalProductEventPublisher.java:42` injects the managed `ObjectMapper`. ✅

**`java.sql.Date` / `Timestamp`** — not used; `java.time` only. ✅

**`HttpURLConnection`** — only in test code (`MediaControllerIT.java:316-329`). ✅

**AWS SDK v2 `S3Client`** — thread-safe by contract. ✅

**ES `ElasticsearchClient`** — thread-safe by contract (the low-level `RestClient` manages a connection pool). ✅

**`java.util.HashMap` / `ArrayList` as a local variable** — fine; locals are thread-confined. ✅

## 10.7 Check-then-act races (complete enumeration)

| Location | Race | Mitigation |
|---|---|---|
| `promotion-service/.../service/impls/CampaignReservationServiceImpl.java:88-103` | COUNT the quota, then INSERT — two concurrent reserves can both pass the PER_USER / MAX_REDEMPTIONS / BUDGET gates | **Mitigated, not eliminated** — `Campaign.@Version` (`:58-61`) + the version-touch at `:109-110` + a 3-attempt retry (`ReservationRetryServiceImpl.java:72-88`). See **CAT-07**. |
| `product-service/.../service/impls/ProductServiceImpl.java` (slug/SKU pre-checks) | `existsBySlug` / `existsBySku` then `save` | **Mitigated by the DB** — partial unique indexes on live rows in the changelog. The pre-check is for UX only. ✅ |
| `media-service/.../service/impls/MediaUploadServiceImpl.java:93` (`findBySha256`) → `:160` (insert) | Two concurrent identical uploads both pass the dedup probe | **Mitigated by the DB** — the unique index on `sha256`; the loser catches `DataIntegrityViolationException` at `:170-177` and resolves to the winner. `MediaUploadServiceImplTest.java:91-111` proves the loser's objects are purged and the winner's media returned. ✅ |
| `favourite-service/.../service/impls/FavouriteServiceImpl.java:50-64` | `existsByUserIdAndProductId` then insert | **Mitigated by the DB** — partial unique index + `DataIntegrityViolationException` catch. ✅ |
| `notification-service/.../service/impls/NotificationServiceImpl.java:61` then `:81` | `existsByEventId` then insert | **Mitigated by the DB** — `uk_notification_event_id` + catch. ✅ |
| `rating-service/.../service/impls/RatingServiceImpl.java:44` then `:66` | `findByUserIdAndProductIdAndDeletedFalse` then insert | **Mitigated by the DB** — `uk_rating_user_product_live` (`changelog-001-ratings.yaml:42`). ✅ |
| `media-service/.../job/MediaPurgeJob.java:29` → `:36` → `:52-57` | Read candidates, check references, then hard-delete | **Acceptable.** The check is a remote call whose failure is fail-safe (`:40-45`), and a media that becomes referenced between the check and the delete would be caught by the *next* cycle's re-check. The product-side `MediaHeadClient` write-time gate (`ProductServiceImpl.java:267`) prevents a new reference from being created against a deleted media. Low risk. |
| `product-service/.../job/ProductMediaSweepJob.java:37` → `:48` → `:52` | Read a page, HEAD each media, then clear | **Fail-safe** — any client exception aborts the whole cycle (`:58-64`). No mass clearing behind doubt. ✅ |

## 10.8 Token-provider thread-safety (the fleet's one inconsistency)

Four services each implement a client-credentials token cache. **Three use a bounded lock; one uses an unbounded monitor.**

| Service | File:line | Mechanism |
|---|---|---|
| product | `security/ServiceTokenProvider.java:61-85` | `AtomicReference<CachedToken>` + `ReentrantLock.tryLock(5, TimeUnit.SECONDS)` — a **bounded** wait. ✅ |
| media | `security/ServiceTokenProvider.java:61-85` | same. ✅ |
| rating | `security/ServiceTokenProvider.java:59-83` | same. ✅ |
| **search** | `security/ServiceTokenProvider.java:59` | `private synchronized String refreshToken()` — an **unbounded** monitor wait. **CAT-14.** |

**CAT-14 impact.** `refreshToken()` performs a synchronous HTTP POST to Keycloak (`ServiceTokenProvider.java:71-76`). While one thread is inside the monitor, every other thread calling `getToken()` (`:50-57`) blocks on the monitor **for the full duration of the HTTP call, with no timeout**. The reindex path (`ReindexServiceImpl.streamSource` → `ProductBackofficeClient.fetchPage`) calls `getToken()` on every page fetch. A hung Keycloak therefore blocks the reindex thread *and* every other thread that needs a token, with no upper bound. The three sibling services avoid exactly this with `tryLock(5, SECONDS)`.

**Note the double-checked caching is otherwise correct in all four:** `getToken()` re-reads the `AtomicReference` outside the lock (`:51-55`) and only enters the refresh path when the cached token is within 30 s of expiry (`REFRESH_SKEW_SECONDS = 30`, `:28`); `refreshToken()` re-checks inside the lock (`:60-64`) so only one HTTP call happens per expiry window.

---

---

# 11. DESIGN PATTERN EVALUATION PER SERVICE

Legend: **PRESENT** = genuinely in the code and load-bearing · **PRESENT-BUT-WEAK** = in the code but not doing the job · **ABSENT** = not present · **JUSTIFIED ADDITION** = should be added.

| Pattern | product | search | media | promotion | tax | rating | favourite | notification |
|---|---|---|---|---|---|---|---|---|
| Repository | PRESENT (JPA only) | **n/a** (no DB) | PRESENT (JPA + native) | PRESENT | PRESENT | PRESENT | PRESENT | PRESENT |
| Domain Events | PRESENT (outbox) | PRESENT (consumer) | PRESENT (outbox) | PRESENT (outbox) | — | PRESENT (outbox) | — | — |
| Cache-Aside | PRESENT (Redis) | — | — | — | — | — | — | — |
| Adapter | PRESENT (storage/media/inventory clients) | PRESENT (ES client, backoffice client) | PRESENT (storage port) | PRESENT (caller guard) | PRESENT (caller guard) | PRESENT (order client) | — | PRESENT (sender port) |
| Strategy | — | — | — | **ABSENT** (if/else) | — | — | — | PRESENT (sender by channel) |
| Specification / Policy | **ABSENT** (JPA `Specification` is a *query* spec, not a business policy) | — | — | **ABSENT** | — | — | — | — |
| State | ABSENT | ABSENT | ABSENT | PRESENT (reservation lifecycle) | — | PRESENT (notification lifecycle) | — | PRESENT (notification status) |
| Factory | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT |
| Template Method | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT | ABSENT |
| Reservation | — | — | — | PRESENT (core) | — | — | — | PRESENT (notification claim) |
| Guard / Fail-closed | PRESENT | PRESENT | PRESENT | PRESENT | PRESENT | PRESENT | PRESENT | PRESENT |

## 11.1 Patterns that are genuinely present and load-bearing

### Domain Events (transactional outbox) — all four event producers
`product-service`, `media-service`, `promotion-service`, `rating-service` each implement the **transactional outbox** properly: the intent row is written in the same transaction as the business change, and a separate relay publishes it.

- **The outbox relay is a genuine, reusable Template-Method-shaped pipeline** realised as three phases: *claim* (pessimistic, SKIP-LOCKED) → *publish* (outside any transaction) → *mark* (compare-and-set under the row lock). `product-service/.../service/OutboxRelay.java:119-124` (claim), `:206` (mark), `:191` (recover).
- **Per-aggregate ordering is a real invariant** the relay enforces: a failed record blocks only the later records of the **same** aggregate, so a consumer never sees a later event of an aggregate before an earlier one (`OutboxRelayTest.java:143-168`). ✅
- **The consumer side is deliberately dumb** — `ProductSearchConsumer.java:44-61` and `ProductRatingConsumer.java` are idempotent copy-readers of a full snapshot. This is the right DDD choice: the producer owns the aggregate invariants, the consumer just applies the snapshot.
- **Snapshot-carrying events** (`TransactionalProductEventPublisher.java:68-90` builds a 17-field payload; `RatingEventService.java:70-83` builds a 13-field payload) mean the consumer needs no joins, no lookups and no source access. ✅

### Reservation — promotion-service (the strongest pattern use in scope)
`promotion-service` models a **reservation** aggregate with an explicit lifecycle (`PENDING → COMMITTED | RELEASED | EXPIRED`), a server-owned TTL, an idempotent `commit`, a compensating `releaseCommitted`, and a query-side `getState` projection. `CampaignReservationServiceImpl.java:142-222`. The `previousStatus` field in the released event (`:187`, `:212`) lets consumers distinguish a plain release from a compensation. ✅

### Reservation — notification-service
`NotificationRetryScheduler.java:96-111` `claim(Instant now)` reserves a batch of notification rows with a pessimistic lock and a heartbeat before sending. Same pattern, different domain. ✅

### State — notification-service
`notification-service/.../service/NotificationWriter.java:39-104` — every transition is guarded by the row's *current* status, so the state machine cannot be short-circuited. The 7-state `NotificationStatus` enum with a widened CHECK constraint in `changelog-002-notification-retry.yaml` and a backfill of pre-existing `FAILED` rows. ✅

### Adapter
- `media-service` → `ObjectStorageService` port with an S3/RustFS adapter. ✅
- `search-service` → `ElasticsearchClient` + `ProductBackofficeClient`. ✅
- `rating-service` → `EligibilityClient` over an HTTP port. ✅
- `product-service` → `MediaHeadClient`, `InventoryReservationClient`. ✅

### Cache-Aside
`product-service/.../config/CacheConfig.java:62-68` + `@Cacheable`/`@CachePut`/`@CacheEvict` on the service methods, with a Redis cache manager and per-cache TTLs. The eviction-on-delete is precise (only the affected keys) and proven (`ProductServiceImplTest.java:244-258`). ✅

### Guard / fail-closed — fleet-wide and consistent
Every remote dependency that answers a *safety* question fails closed:
`MediaHeadClient.java:47-62`, `InventoryReservationClient.java:53-71`, `MediaReferenceClient.java:56-75`, `EligibilityClient.java:50-69`, `ProductMediaReferenceChecker.java:24-35`. ✅ This is a genuinely strong, uniform discipline across all eight services.

## 11.2 Patterns that are absent, and the ones that would be justified additions

### JUSTIFIED ADDITION 1 — a `ReservationGate` chain in promotion-service (CAT-16)

The if/else chain at `CampaignReservationServiceImpl.java:54-134` is 80 lines mixing five concerns. The right abstraction is **not** a per-gate Strategy (the gates are ordered predicates with side effects, not interchangeable algorithms) but an ordered **policy chain**:

```java
public interface ReservationGate {
    void check(Campaign campaign, ReserveRequest request, ReservationContext context);
    int order();
}

public record ReservationContext(BigDecimal discount, Instant now) { }
```

```java
@Component
public class PerUserLimitGate implements ReservationGate {
    private final CouponUsageReservationRepository reservations;
    public int order() { return 40; }
    public void check(Campaign campaign, ReserveRequest request, ReservationContext context) {
        if (campaign.getPerUserLimit() > 0
                && reservations.countByCampaignIdAndUserIdAndStatusIn(
                        campaign.getId(), request.userId(), COUNTED_STATUSES) >= campaign.getPerUserLimit()) {
            throw BusinessException.of(ErrorCode.PER_USER_LIMIT_EXCEEDED, request.userId());
        }
    }
}
```

```java
@Component
public class ReservationGateChain {
    private final List<ReservationGate> gates;
    public ReservationGateChain(List<ReservationGate> gates) {
        this.gates = gates.stream().sorted(Comparator.comparingInt(ReservationGate::order)).toList();
    }
    public void evaluate(Campaign campaign, ReserveRequest request, ReservationContext context) {
        for (ReservationGate gate : gates) {
            gate.check(campaign, request, context);
        }
    }
}
```

**Why this is justified:** the gate order is already documented as load-bearing (`CampaignReservationServiceImpl.java:48-53`), it is already pinned by 8 separate tests in `ReserveFlowIT.java:167-270` (one per gate), and adding a new gate type (e.g. "one redemption per email address") currently requires editing the reserve method. The chain makes the order *data* (the `order()` value) instead of *control flow*, and makes each gate independently testable. **The tests already exist and would survive the refactor unchanged** — that is the acceptance criterion.

### JUSTIFIED ADDITION 2 — a `DiscountPolicy` in promotion-service

`DiscountCalculator.java:24-33` is a `static` if/else on `discountType`. Two types today; a third (e.g. `FREE_SHIPPING`, `BUY_X_GET_Y`) would require editing the class. A small strategy is justified **only** when a third type is actually planned — today it is a premature abstraction. **Recommendation: defer.** If a third type arrives, use:

```java
public interface DiscountPolicy {
    boolean supports(String discountType);
    BigDecimal compute(BigDecimal discountValue, BigDecimal orderAmount);
}

@Component
public class PercentDiscountPolicy implements DiscountPolicy { /* amount × value / 100, scale 2, HALF_UP */ }

@Component
public class FixedDiscountPolicy implements DiscountPolicy { /* min(value, amount) */ }
```

### JUSTIFIED ADDITION 3 — a `ProductIndexDocument` factory + a versioned write in search-service (CAT-01, CAT-04)

`ProductDocuments.of(...)` is a static map builder shared by `ProductSearchService.index()` and `ReindexServiceImpl.bulkIndex()`. Two things are missing:
1. **A version/ordering precondition on the ES write** so an out-of-order event cannot overwrite newer state.
2. **A `scaled_float` mapping for `price`** so the money survives the round trip.

Concrete sketch for the versioned write (requires the producer to carry a monotonic version — see CAT-01's proposed fix):

```java
public void index(ProductLifecycleEvent event) {
    if (!STATUS_ACTIVE.equals(event.status())) {
        delete(event.productId());
        return;
    }
    Map<String, Object> document = ProductDocuments.of(event);
    try {
        client.index(i -> i
                .index(IndexProvisioner.ALIAS)
                .id(event.productId().toString())
                .version(event.version())            // monotonic per-product version
                .versionType(VersionType.External)  // the producer owns the ordering
                .document(document));
    } catch (ElasticsearchException ex) {
        if (ex.status() == 409) {
            log.debug("Stale product event {} ignored", event.productId());
            return;
        }
        throw BusinessException.of(ErrorCode.SEARCH_INDEX_FAILED, event.productId());
    }
}
```

For `price`, the mapping becomes:
```java
fields.put("price", Property.of(p -> p.scaledFloat(s -> s.scalingFactor(100))));
```
which stores `999.99` as the integer `99999` — exact, and range-queryable at the cent.

### JUSTIFIED ADDITION 4 — a `Money` value object

The fleet-wide absence of a currency (CAT-05) and of a consistent rounding regime (CAT-21) is exactly the problem a `Money` value object solves:

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("scale exceeds " + currency.getDefaultFractionDigits());
        }
    }
    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch");
        }
        return new Money(amount.add(other.amount), currency);
    }
}
```

**This is a large change** (it touches every DTO, entity, event payload and the ES mapping), so it should be a **deliberate, fleet-wide initiative**, not a per-service patch. The audit's recommendation is: introduce it at the **boundary** first — the tax-service request/response DTO and the promotion reservation DTO — and let it propagate inward.

### NOT justified — Factory
No service needs a Factory. The only candidate is media-service's variant rendering, but the three variants and two formats are a **fixed, known enumeration**; `VariantRenderer.render()` returning a `List<Render>` is simpler and more honest than a factory hierarchy. Adding one would be ceremony.

### NOT justified — Template Method
Every service uses plain composition. The outbox relay is the closest thing to a Template Method, but its three phases are **not** an algorithm with variable steps — they are a fixed pipeline expressed as three methods. An abstract base class with `doClaim/doPublish/doMark` hooks would add a class without adding a seam. The current shape (a `@Service` with three private methods) is correct.

### NOT justified — Specification / Policy (as a general pattern)
`ProductServiceImpl` uses JPA `Specification<Product>` for **query building** — that is Spring Data's criteria API, not DDD's Specification pattern. A DDD Specification would be a composable business predicate (`IsSellable`, `IsInStock`) used to decide *whether* something is allowed. The fleet does not have that, and for these services the business predicates are expressed as explicit `if` statements in the service — which is **readable and appropriate** for a rule set of this size. A Specification layer would be justified only if the same rule had to be applied in three places (API, reindex stream, ES query). It does not.

### Weak but present — Specification in product-service
`ProductServiceImpl.java:32` imports `org.springframework.data.jpa.domain.Specification` — this is the one place where a *persistence* type appears in an *application* service signature. It is not a DDD Specification and should not be mistaken for one. The leak is real but low-impact (see §2.0).

## 11.3 Per-service verdict on pattern usage

| Service | Pattern verdict |
|---|---|
| **product** | Strong: outbox + domain events + cache-aside + precise eviction + fail-closed guards + transaction-boundary discipline (F-066). Weak: JPA `Specification` in the application service; no aggregate boundary between Product and ProductVariant. |
| **search** | Strong: hexagonal boundary (no ORM at all), correct alias-swap reindex with abort-without-swap semantics, bidirectional status handling. Weak: no ordering precondition on the write (CAT-01); in-process-only reindex lock (CAT-08); float money mapping (CAT-04); dependency drift (CAT-02). |
| **media** | Strong: port/adapter for storage, content-addressed dedup with a race-safe resolution, precise MIME + magic-byte gate, decoded-byte budget before decode, fail-safe reference checks, objects-then-rows purge. Weak: no `Money`/currency (n/a — media has no money); no DEAD aging for FAILED (n/a — media *has* it). |
| **promotion** | Strongest DDD posture: a real reservation aggregate, a state machine with compensation, injected `Clock`, optimistic-locking version columns, a conditional-UPDATE claim, and an atomic budget-quota return. Weak: the gate chain is an if/else (CAT-16); the quota is a COUNT not an atomic counter (CAT-07). |
| **tax** | Small, pure, and correct: a pure static calculator with an explicit scale and rounding mode. Weak: no currency (CAT-05), no rounding-regime contract (CAT-21), no net/gross flag (CAT-22). |
| **rating** | Strong: verified-buyer derived from the owning context and fail-closed; duplicate prevention at the DB level; a live aggregate query instead of a stored counter; flush-before-record discipline. Weak: the snapshot can be stale under concurrency (CAT-19); no FAILED→DEAD aging (CAT-10). |
| **favourite** | Minimal and correct: owner-scoped atomic SQL, JWT-derived identity, DB-level uniqueness. No pattern gaps. |
| **notification** | Strong: a guarded state machine, a bounded+durable retry policy with overflow-safe backoff, a pessimistic claim with a heartbeat, and a permanent-vs-retryable classifier. Weak: no retention purge for the notifications table (CAT-23). |

---

---

# 12. DEFECT LIST

Severity: **HIGH** = production data corruption / silent loss / money correctness · **MEDIUM** = correctness under concurrency or operational risk · **LOW** = hardening / contract gap · **INFO** = consistency.

| ID | Sev | Service | One-line |
|---|---|---|---|
| CAT-01 | HIGH | search | Out-of-order events silently overwrite newer document state (no version precondition) |
| CAT-02 | HIGH | search | ES client dependency drift: 8.15.5 pinned over the managed 9.4.5 |
| CAT-03 | MEDIUM | product | `ProductMediaSweepJob.currentPage` — mutable, non-volatile state on a singleton |
| CAT-04 | MEDIUM | search | ES `price` mapped as `double_` — money stored as an IEEE-754 float |
| CAT-05 | HIGH | fleet | No currency on **any** money field in any of the eight services |
| CAT-06 | MEDIUM | product | Product-variant changes emit no lifecycle event → never reach the index |
| CAT-07 | HIGH | promotion | Per-user / max-redemption / budget quotas enforced by a racy read-then-write COUNT |
| CAT-08 | MEDIUM | search | Reindex lock is an in-process `AtomicBoolean` — no cross-pod protection |
| CAT-09 | MEDIUM | product | Outbox claim lease piggy-backs on the `updated_at` audit column (no `claimed_at`) |
| CAT-10 | MEDIUM | product, rating, promotion | `FAILED` outbox rows are terminal: never replayed, never aged to `DEAD` |
| CAT-11 | MEDIUM | media | No concurrency admission control on the image decode path |
| CAT-12 | LOW | media | 7-day presigned-URL TTL for catalogue images |
| CAT-13 | MEDIUM | search | No mapping-version gate — a stale template silently propagates to every new index |
| CAT-14 | LOW | search | `ServiceTokenProvider.refreshToken()` uses an unbounded `synchronized` monitor |
| CAT-15 | LOW | media | Bucket ACL is asserted once at startup; a later change to public goes undetected |
| CAT-16 | MEDIUM | promotion | Reservation gates are an if/else chain — not extensible without editing the method |
| CAT-17 | MEDIUM | media | `FAILED` outbox rows are re-published every 2 s for 7 days with no backoff |
| CAT-18 | INFO | fleet | Event-naming convention is inconsistent across producers |
| CAT-19 | MEDIUM | rating | The aggregate snapshot in the rating event can be stale under concurrent reviews |
| CAT-20 | LOW | rating | `Rating.verified` is stored but never enforced on the read path |
| CAT-21 | LOW | tax | Rounding regime (HALF_UP) is neither documented nor configurable |
| CAT-22 | LOW | tax | The pricing contract has no net/gross declaration |
| CAT-23 | MEDIUM | notification | No retention purge for the `notifications` table |
| CAT-24 | LOW | product, media, promotion, rating | `@Value`-injected configuration fields are non-final on singletons |
| CAT-25 | LOW | promotion | `CouponUsageReservation.@Version` is hand-bumped inside a JPQL string — two owners of one invariant |

---

## CAT-01 — Out-of-order Kafka events silently overwrite newer Elasticsearch state

| | |
|---|---|
| **Severity** | HIGH |
| **Service** | search-service |
| **Evidence** | `search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java:43-46` |

```java
client.index(i -> i
    .index(IndexProvisioner.ALIAS)
    .id(event.productId().toString())
    .document(document));
```

**Actual behaviour.** The write is an unconditional Elasticsearch `index`. There is no `version`, no `version_type`, no `if_seq_no`/`if_primary_term`, no `external_gte`. A case-insensitive grep for `version|seq_no|seqNo|if_seq_no|external` across `search-service/src/main/java` returns **0 hits**. The event *does* carry `occurredAt` and `updatedAt` (`ProductLifecycleEvent`), but neither is consulted for ordering — they are copied into the document as data (`IndexProvisioner.java:76`).

**Violated invariant.** *"The indexed document must reflect the latest committed state of the product."* A stale event arriving late violates it silently.

**Root cause.** The document id is stable and correct (`ProductSearchService.java:45`), but the producer's ordering is not carried into the write. The product-service relay publishes per-aggregate in ascending id order (`OutboxRelay.java:245` `orderById`), which normally prevents this — but Kafka redelivery after a consumer restart/rebalance, and multiple producer instances, break that guarantee.

**Impact.** The index permanently shows a stale price / title / description. No retry, reconciliation or automatic reindex corrects it (`ReindexServiceImpl.java:35-36` — reindex is a manual, operator-triggered endpoint). A price regression in the storefront is a customer-facing and revenue-affecting defect.

**Proposed fix.** (a) Add a monotonic per-product `version` to the product lifecycle payload (the product row already has `updatedAt`; a dedicated `eventVersion` counter is cleaner). (b) Write with `.version(event.version()).versionType(VersionType.External)` and treat ES `409 Conflict` as "stale event, ignore". (c) Short term, until (a) lands, compare `event.updatedAt()` against the stored document's `updatedAt` and skip the write when the incoming one is older.

**Reproduction test.** `search-service/src/test/java/com/shop/searchservice/kafka/ProductSearchIngestionIT.java` — new test: publish `ProductUpdated(price=39.99, updatedAt=T2)`, wait for the doc, then publish `ProductUpdated(price=25.50, updatedAt=T1 < T2)`; assert the indexed `price` is still `39.99`.

**Acceptance criterion.** `ProductSearchIngestionIT` contains an out-of-order test that fails against the current code and passes after the fix; the ES write carries a version precondition; a stale event never mutates a document.

---

## CAT-02 — Elasticsearch client dependency drift

| | |
|---|---|
| **Severity** | HIGH |
| **Service** | search-service |
| **Evidence** | `search-service/pom.xml:36-40` vs `pom.xml:55`, `pom.xml:206-207` |

```xml
<!-- search-service/pom.xml:36-40 -->
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <version>8.15.5</version>
</dependency>
```
```xml
<!-- pom.xml:55 -->
<elasticsearch-client.version>9.4.5</elasticsearch-client.version>
<!-- pom.xml:206-207 -->
<artifactId>elasticsearch-java</artifactId>
<version>${elasticsearch-client.version}</version>
```

**Actual behaviour.** search-service overrides the root POM's managed version with `8.15.5`. Every other service inherits `9.4.5`. The fleet runs two different major versions of the ES Java client.

**Violated invariant.** *"One managed version of every shared dependency across the fleet."* The root POM exists precisely to make dependency versions single-sourced.

**Root cause.** A local `<version>` element added to override an inherited managed version — the standard Maven escape hatch, used here without recording the reason. The drift was not caught because the hand-rolled `RestClientTransport` wiring (`config/ElasticsearchConfig.java:28-48`) compiles against the 8.x API surface and the service's own ITs run against a matching ES container.

**Impact.** The elastic client's compatibility matrix does not certify an 8.x client against a 9.x server. Any fleet-wide ES server upgrade will hit this service last and hardest. It also defeats CVE-response coordination: a security patch to `elasticsearch-java` reaches seven services and misses the eighth.

**Proposed fix.** Delete `<version>8.15.5</version>` from `search-service/pom.xml` so the managed `9.4.5` applies, and adjust `ElasticsearchConfig`/call sites for the 9.x API. If 8.15.5 is genuinely required, the *root* POM property must be changed to 8.15.5 and every other service updated in the same commit.

**Reproduction test.** A build-level assertion: `./mvnw dependency:tree -pl search-service` shows `co.elastic.clients:elasticsearch-java` at exactly `${elasticsearch-client.version}`; equivalently a `maven-enforcer-plugin` `requireUpperBoundDeps`/`bannedDependencies` rule.

**Acceptance criterion.** `grep -rn "elasticsearch-java" --include=pom.xml` shows **no** `<version>` element outside the root POM's `<dependencyManagement>`; all services resolve the same `elasticsearch-java` version.

---

## CAT-03 — Mutable, non-volatile pagination state on a singleton scheduled bean

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | product-service |
| **Evidence** | `product-service/src/main/java/com/shop/productservice/job/ProductMediaSweepJob.java:27`, `:29`, `:38-43` |

```java
27:     private int currentPage = 0;
29:     @Scheduled(cron = "${shop.product.media-sweep.cron:0 */30 * * * *}")
30:     public void sweep() {
…
37:             var page = productRepository.findByMediaIdIsNotNull(
38:                 PageRequest.of(currentPage, properties.limit()));
39:             if (page.isEmpty() || !page.hasNext()) {
40:                 currentPage = 0;
41:             } else {
42:                 currentPage++;
43:             }
```

**Actual behaviour.** A plain `int` field on a singleton bean is read at `:38` and written at `:40`/`:42` with no `volatile`, no `synchronization` and no atomic wrapper. The read-modify-write `currentPage++` is not atomic, and without `volatile` the write is not guaranteed to be visible to another thread (JMM §17.4).

**Violated invariant.** *"A scheduled job must be safe under concurrent execution and must not lose or skip rows."* Two failures: (a) a lost/duplicated page increment; (b) the derived query `findByMediaIdIsNotNull(Pageable)` has **no `ORDER BY`**, so with a mutating row set the cursor both skips and re-visits rows.

**Root cause.** A pagination cursor was modelled as *bean state* rather than derived from a stable ordering. The sweep is a periodic reconciliation over **all** media-referencing products — the cursor buys nothing.

**Impact.** With the default scheduler pool size of 1 the race is latent. Raise `spring.task.scheduling.pool.size` to ≥ 2 (a routine tuning change) and two sweeps overlap: the page cursor is corrupted and coverage becomes non-deterministic. Each cycle also performs one remote HEAD per row (`MediaHeadClient.exists`, `:48`), so the sweep is long-running and overlap is plausible.

**Proposed fix.** Delete the field. Always query `PageRequest.of(0, properties.limit())` — one page per cycle, from the start. If a cursor is genuinely required later, it must be an `AtomicInteger` reset at the start of each cycle and the query must have a deterministic `ORDER BY`.

**Reproduction test.** `product-service/src/test/java/com/shop/productservice/job/ProductMediaSweepJobTest.java` — a new test that calls `sweep()` from two threads with a `CyclicBarrier` and asserts both observe a consistent page index; plus a test asserting `findByMediaIdIsNotNull` is always called with `PageRequest.of(0, limit)`.

**Acceptance criterion.** `ProductMediaSweepJob` has no non-final instance field other than injected collaborators; `ProductMediaSweepJobTest` asserts the page index is always 0.

---

## CAT-04 — Money stored as an IEEE-754 double in the search index

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | search-service |
| **Evidence** | `search-service/src/main/java/com/shop/searchservice/search/IndexProvisioner.java:72` |

```java
fields.put("price", Property.of(p -> p.double_(d -> d)));
```

**Actual behaviour.** `double_` stores an IEEE-754 64-bit float. `999.99` is stored as `999.9899999999999863575794734060764312744140625`.

**Violated invariant.** *"A money value must round-trip exactly and be range-queryable at its unit of account."* Both fail: the value is not exact, and a `price >= 999.99` filter can exclude a product priced exactly 999.99 (its stored double is strictly below 999.99).

**Root cause.** The default ES numeric type was chosen for a `BigDecimal` source without considering that `BigDecimal` is not a float. `avgRating` (`:73`) uses `halfFloat` — a 16-bit float — for a `BigDecimal(precision=3, scale=2)` source.

**Impact.** Off-by-a-cent boundary errors in price filters, and a float-rendered price in the storefront. The existing test papers over it: `ProductSearchIngestionIT.java:110` asserts `((Number) doc.get("price")).doubleValue()).isEqualTo(25.50d)` — it converts through `doubleValue()` and therefore cannot detect the representation error.

**Proposed fix.** Use `scaled_float` with `scalingFactor(100)` for `price` (stores `999.99` as the integer `99999` — exact and correctly range-queryable). For `avgRating`, use `scaled_float` with `scalingFactor(100)` as well. Requires a mapping change and a reindex.

**Reproduction test.** `search-service/src/test/java/com/shop/searchservice/search/SearchIndexProvisioningIT.java` — assert the live mapping for `price` is `scaled_float` with `scaling_factor == 100`; and in `ProductSearchIngestionIT`, index `price = 999.99` and read the raw `_source` back asserting the value is exactly `999.99` (as a decimal string), not a double.

**Acceptance criterion.** `IndexProvisioner.productMapping()` contains no `double_` and no `halfFloat`; the mapping IT asserts `scaled_float` for every monetary/decimal field.

---

## CAT-05 — No currency on any money field in the fleet

| | |
|---|---|
| **Severity** | HIGH |
| **Service** | all eight |
| **Evidence** | `product-service/.../entity/Product.java:38-39`; `promotion-service/.../entity/CouponUsageReservation.java:52-55`; `promotion-service/.../entity/Campaign.java:36`; `tax-service/.../service/TaxCalculator.java:13-16`; `rating-service/.../entity/Rating.java:40` (no money) |

```java
// product-service/.../entity/Product.java:38-39
@Column(name = "price_unit", nullable = false, precision = 15, scale = 2)
private BigDecimal priceUnit;
```

**Actual behaviour.** A case-insensitive search for `currency` across all eight services' `.java`, `.yaml` and `.yml` returns **7 hits, every one of them the word "Concurrency"** in comments and test-class names. There is **zero** currency column, field, enum or ISO-4217 code in the fleet. `price_unit`, `product_variants.price`, `campaigns.total_budget`, `coupon_usage_reservations.order_amount` / `discount_amount` and the tax-service calculation result are all bare decimals.

**Violated invariant.** *"A monetary amount is meaningless without a unit of account."* A `price_unit` column can simultaneously hold USD, VND, EUR and JPY amounts, and nothing in the schema, the code, the API or the events can distinguish them.

**Root cause.** The bounded contexts were modelled independently and each treated "amount" as sufficient. No cross-context money contract was ever defined — which is also why the tax↔order pricing contract (§8.3) is just an endpoint.

**Impact.** Multi-currency is impossible to implement safely without a migration of every money column, every DTO, every event payload and the ES mapping. Any attempt to introduce it incrementally will produce a period where the same column holds two currencies — the worst possible state. The presence of `messages_vi.properties` and a VI locale across the fleet indicates a Vietnamese-market deployment (VND has **0** decimal places, unlike the hard-coded `scale = 2` used everywhere), so this is not hypothetical.

**Proposed fix.** Introduce a `Money` value object (§11.2) and a fleet-wide `shop.money.default-currency` property. Apply it **at the boundaries first** — tax-service request/response DTOs and promotion reservation DTOs — and propagate inward. Add `currency CHAR(3)` columns alongside the existing amounts, backfilled with the default currency, before any multi-currency traffic is accepted.

**Reproduction test.** A schema-level assertion test per service: for every column named `*price*`, `*amount*`, `*budget*` or `*discount*`, assert a sibling `currency` column exists. Plus a contract test: `TaxCalculateResponse` and `ReservationResponse` carry a non-null currency.

**Acceptance criterion.** Every monetary column has a currency column; every monetary DTO and event payload carries a currency; the fleet's default currency is a single configured property.

---

## CAT-06 — Product-variant changes emit no lifecycle event

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | product-service |
| **Evidence** | `product-service/src/main/java/com/shop/productservice/service/impls/ProductVariantServiceImpl.java` (no `publish*` call anywhere in the file); `product-service/src/main/resources/db/changelog/changelog-006-product-variants.yaml:7` |

**Actual behaviour.** `grep` for `publish` / `Event` in `ProductVariantServiceImpl.java` returns **nothing**. Variant create/update/delete mutate `product_variants` and publish **no** lifecycle event. The table was added by `changelog-006-product-variants.yaml` and has an index on `(product_id)` (`:82`) and a partial unique index on `sku` (`:89-90`) — it is a first-class, indexed, priced catalogue table with no propagation.

**Violated invariant.** *"Every change to a published aggregate must reach its read models."* The Kafka lifecycle topic `shop.product.lifecycle.v1` is the contract between the catalogue and the search index; the variant aggregate sits outside it.

**Root cause.** The event publisher was added for Product/Category/Brand (`TransactionalProductEventPublisher`, `TransactionalCategoryEventPublisher`, `TransactionalBrandEventPublisher`) and the variant aggregate — added later by a separate changelog — was never wired in.

**Impact.** Variant prices, SKUs and attributes never appear in the search index. A customer searching by a variant SKU finds nothing; a variant price change is invisible to search. The searchable price is always the parent's `price_unit`, which is wrong for any catalogue that prices per variant.

**Proposed fix.** Add a `TransactionalProductVariantEventPublisher` mirroring the existing three, and publish `ProductVariantCreated` / `ProductVariantUpdated` / `ProductVariantDeleted` in the same transaction as the variant write. On the search side, either index variants as nested documents or (simplest, matching today's model) publish a `ProductUpdated` event when a variant change alters the parent's effective price.

**Reproduction test.** `product-service/src/test/java/com/shop/productservice/service/ProductVariantServiceTest.java` — extend the existing `create_success` / `delete_success` tests to assert the publisher was called; and a new IT asserting a `PENDING` outbox row exists after a variant create.

**Acceptance criterion.** Every mutation of `product_variants` produces exactly one outbox row in the same transaction; `ProductVariantServiceTest` asserts the publisher invocation.

---

## CAT-07 — Promotion quotas are enforced by a racy read-then-write COUNT

| | |
|---|---|
| **Severity** | HIGH |
| **Service** | promotion-service |
| **Evidence** | `promotion-service/src/main/java/com/shop/promotionservice/repository/CouponUsageReservationRepository.java:24-31`; `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java:88-103`, `:105-110` |

```java
// CouponUsageReservationRepository.java:24-31
long countByCampaignIdAndUserIdAndStatusIn(UUID campaignId, UUID userId, Collection<UsageStatus> statuses);
long countByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);
@Query("select coalesce(sum(r.discountAmount), 0) from CouponUsageReservation r " +
       "where r.campaignId = :campaignId and r.status in :statuses")
BigDecimal sumDiscountByCampaignIdAndStatusIn(...);
```
```java
// CampaignReservationServiceImpl.java:88-92
if (campaign.getPerUserLimit() > 0
        && reservationRepository.countByCampaignIdAndUserIdAndStatusIn(
            campaign.getId(), request.userId(), COUNTED_STATUSES) >= campaign.getPerUserLimit()) {
    throw BusinessException.of(ErrorCode.PER_USER_LIMIT_EXCEEDED, request.userId());
}
```

**Actual behaviour.** The three quota gates (`per_user_limit`, `max_redemptions`, `total_budget`) are **read** queries evaluated at `:88-103`; the write happens later at `:124`. There is **no** counter column, **no** `UPDATE … SET used = used + 1 WHERE used < limit`, and **no** `SELECT … FOR UPDATE` on the campaign. The only DB-level uniqueness is `uk_cur_order_id` (`changelog-001-initial-schema.yaml:118-123`) — one reservation per **order**, i.e. idempotency, not quota enforcement.

**Violated invariant.** *"A coupon may be redeemed at most N times (per user / per campaign) and its budget may not be exceeded."* Today this is upheld only indirectly, by optimistic locking on an unrelated column.

**Root cause.** The concurrency control is an **optimistic** version-touch on the campaign's audit timestamp:
```java
// CampaignReservationServiceImpl.java:105-110
// ⚠️ Race protection (spec §5.2) — explicit choice: version-touch on the campaign row.
campaign.setUpdatedAt(now);
campaignRepository.saveAndFlush(campaign);
```
The `@Version` column (`entity/Campaign.java:58-61`) makes the losing transaction fail with `OptimisticLockingFailureException`; `ReservationRetryServiceImpl.java:72-88` retries it up to 3 times with a 50 ms linear backoff.

**Impact.**
- The **outcome** is correct under the tested contention: `ReserveFlowIT.java:274-327` proves that 8 concurrent reserves against `per_user_limit = 3` yield exactly 3 successes and 5 failures, with the DB invariant holding (`:322-327`).
- But the **retry budget is the binding constraint**, not the limit: 5 of 8 attempts ended in `PRO-7006` **or `PRO-7011`** (`:317-319`). `PRO-7011` is retry-exhaustion — a 500-class error for a request that merely needs to be retried later.
- Every reserve writes the campaign row, making the most valuable row of the table a **hot row** with write amplification proportional to redemption traffic.
- The limit is **per campaign**, not per coupon: there is no `Coupon` entity; `per_user_limit` and `max_redemptions` are campaign attributes (`entity/Campaign.java:45-52`).

**Proposed fix.** Replace the three COUNT gates with a single **atomic conditional UPDATE** on counter columns maintained on `campaigns`:
```sql
UPDATE campaigns
   SET per_user_used = per_user_used + 1, used_count = used_count + 1, budget_used = budget_used + :discount
 WHERE id = :id
   AND (per_user_limit IS NULL OR per_user_used < per_user_limit)
   AND (max_redemptions IS NULL OR used_count < max_redemptions)
   AND (total_budget IS NULL OR budget_used + :discount <= total_budget)
```
The affected-row count is the gate; a 0-row result maps to PRO-7006/PRO-7007. The per-user counter lives in a `campaign_user_usage(campaign_id, user_id, used_count)` table with its own atomic UPDATE. The version-touch at `:109-110` then disappears, the hot row is gone, and no retry loop is needed.

**Reproduction test.** `promotion-service/src/test/java/com/shop/promotionservice/service/ReserveFlowIT.java` — raise the contention from 8 threads to 32 against `per_user_limit = 3` and assert **all 29 failures are `PRO-7006`** (never `PRO-7011`). The current implementation fails this; the atomic-counter implementation passes it.

**Acceptance criterion.** The quota gates are single-statement, database-enforced conditional UPDATEs; no `countBy…` / `sumDiscountBy…` query participates in a quota decision; no reserve path writes the campaign row.

---

## CAT-08 — Reindex lock is in-process only

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | search-service |
| **Evidence** | `search-service/src/main/java/com/shop/searchservice/service/impls/ReindexServiceImpl.java:49`, `:53` |

```java
49:     private final AtomicBoolean reindexInProgress = new AtomicBoolean(false);
…
53:         if (!reindexInProgress.compareAndSet(false, true)) {
54:             throw BusinessException.of(ErrorCode.SEARCH_REINDEX_IN_PROGRESS);
55:         }
```

**Actual behaviour.** The mutual exclusion is an in-memory flag. Two **pods** each run `reindex()` concurrently with no interference: both compute `nextIndexName()` from the same `max(products-v*)` (`:131-148`), both stream the source, and both call `swapAlias` (`:176-186`). The `swapAlias` removes the alias from `products-v*` and adds it to the new index — so the second swap **detaches the alias from the index the first one just published** and points it at its own.

**Violated invariant.** *"At most one reindex runs at a time; the alias always points at a complete, reviewed index."* The AtomicBoolean gives zero protection across JVMs.

**Root cause.** A deliberate MVP simplification, acknowledged in the source at `:32-34`: "an in-process `AtomicBoolean` lock rejects a second concurrent run with 409 SRH-12001 (single-instance MVP posture — a multi-node deployment needs a distributed lock first)."

**Impact.** In a multi-replica deployment a double reindex produces an index that was never validated by the operator, and one of the two runs leaves an orphan index behind (reaped by the next run's `deleteSupersededIndices`, `:189-201`). The `createIndex` collision (`resource_already_exists_exception` → 503) catches the *name* collision but not the alias race.

**Proposed fix.** Use a database-backed advisory lock (Postgres `pg_try_advisory_lock` via a `JdbcTemplate`, or a `SELECT … FOR UPDATE` on a single-row lock table) so the lock is cluster-wide. Keep the `AtomicBoolean` as a fast in-process short-circuit.

**Reproduction test.** `search-service/src/test/java/com/shop/searchservice/service/impls/ReindexServiceTest.java` — a test that calls `reindex()` from two threads and asserts exactly one proceeds; and a two-context test (two Spring contexts against the same Postgres) proving the second is rejected with SRH-12001.

**Acceptance criterion.** The reindex lock is held in a cluster-wide resource (DB advisory lock), so two pods cannot reindex concurrently; the in-process flag remains only as an optimisation.

---

## CAT-09 — Product outbox claim lease piggy-backs on the `updated_at` audit column

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | product-service |
| **Evidence** | `product-service/src/main/java/com/shop/productservice/entity/OutboxEvent.java:18-55` (no `claimed_at`, no `failed_at`); `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java:73-84` |

```java
// OutboxEventRepository.java:73-84
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE OutboxEvent e SET "
     + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
     + "e.retryCount = e.retryCount + 1, "
     + "e.lastError = :reason "
     + "WHERE e.status = :sending AND e.updatedAt < :cutoff")
```

**Actual behaviour.** The claim lease is the `@LastModifiedDate` `updated_at` column inherited from `AbstractMappedEntity` — an **audit** column doubling as a **lease** column. The test documents it: `OutboxRelayRecoveryIT.java:183-188` "This service's outbox row has no dedicated claim column — the relay's claim is what refreshes `updated_at`". media-service (`changelog-004-media-outbox-claim-lease.yaml`), rating-service (`changelog-002-rating-outbox-claim-lease.yaml`) and promotion-service all have a real `claimed_at` column plus an index on it.

**Violated invariant.** *"The lease clock measures only the claim; it is not perturbed by unrelated writes to the row."*

**Root cause.** The `claimed_at` column was added to media and rating in dedicated changelogs but never back-ported to product-service.

**Impact.** Today the risk is contained (nothing else writes `outbox_events`). The moment anything else does — a manual operator `UPDATE` to unblock a stuck event, a future "resend" feature, a bulk re-publish tool — the write refreshes `updated_at` and **silently extends the lease of a row that is already stranded**, keeping the event unpublished. The defect is a latent trap, not an active bug.

**Proposed fix.** Add `claimed_at TIMESTAMP` + an index on it via a new Liquibase changelog, set it in the claim phase (`OutboxRelay.java:119-124` already sets `status = SENDING` — add `event.setClaimedAt(Instant.now())`), clear it on the mark phase, and change `reclaimStaleSending` to `WHERE e.claimedAt < :cutoff`.

**Reproduction test.** `product-service/src/test/java/com/shop/productservice/service/OutboxRelayRecoveryIT.java` — the existing `ageClaim` helper currently writes `updated_at` (`:189-196`); after the fix it writes `claimed_at`. Add a test that bumps `updated_at` on a stranded row and asserts the row is **still** reclaimed (proving the lease no longer depends on the audit column).

**Acceptance criterion.** `outbox_events` has a `claimed_at` column with an index; `reclaimStaleSending` filters on `claimed_at`, not `updated_at`; a stranded row is reclaimed even when `updated_at` has been bumped.

---

## CAT-10 — `FAILED` outbox rows are terminal in product, rating and promotion (no replay, no DEAD aging)

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | product-service, rating-service, promotion-service |
| **Evidence** | `product-service/.../service/OutboxRelay.java:119` (`claimOnePending(OutboxStatus.PENDING)` — PENDING only); `product-service/.../service/OutboxRetentionScheduler.java:36-37` (purges SENT only); `rating-service/.../outbox/OutboxRetentionScheduler.java:34-35` (purges SENT only); `promotion-service/.../service/OutboxRetentionScheduler.java:30-31` (purges SENT only) |

**Actual behaviour.** `OutboxStatus` has a `DEAD` value (`utils/common-core/.../OutboxStatus.java`) documented as "terminal failure … FAILED rows older than the retention window are parked here + WARN + metric, and are never replayed". **Only media-service implements that ladder** (`outbox/OutboxEventRepository.java:55-58` `ageDeadFailedBefore`, `outbox/MediaOutboxRetentionScheduler.java:37-42`). In product-service a row that exhausts its retry budget is set to `FAILED` (`OutboxRelay.java:222`) and:
- is **never re-claimed** — `claimOnePending` selects `status = PENDING` only (`:119`);
- is **never aged to `DEAD`** — no `ageDeadFailedBefore` method exists in `product-service/.../repository/OutboxEventRepository.java` (98 lines, verified);
- is **never purged** — the retention scheduler deletes `SENT` rows only (`OutboxRetentionScheduler.java:36-37`);
- has **no `failed_at` column** — `entity/OutboxEvent.java:18-55` has `sentAt` and `lastError` only.

The same holds for rating-service (`outbox/OutboxEvent.java:53-54` has `claimed_at` but **no** `failed_at`; `OutboxRetentionScheduler.java:34-35` purges SENT only) and promotion-service (`entity/OutboxEvent.java:82` has `claimed_at`, no `failed_at`; `OutboxRetentionScheduler.java:30-31` purges SENT only).

**Violated invariant.** *"A publish failure is either recoverable or explicitly parked — never silently abandoned."* A `FAILED` row here is abandoned: it sits in the table forever with no age, no `DEAD` marker and no metric.

**Root cause.** The media relay's replay + DEAD-aging design (documented as "H-5") was implemented in media-service but never propagated to the other three producers.

**Impact.** A transient Kafka outage (or a broker-side rejection of one specific payload) that exhausts the retry budget **silently drops the event**. The product's new price never reaches the search index; the rating never reaches product-service; the promotion reservation never reaches its consumers. There is no alarm (no DEAD metric) and the row is invisible to the retention sweep — an operator has to know to look for `FAILED` rows.

**Proposed fix.** Port media-service's ladder to product, rating and promotion: add `failed_at TIMESTAMP`; on retry exhaustion set `status = FAILED` **and** `failed_at = now()`; re-claim `FAILED` rows whose `failed_at` is older than a backoff window; age rows past the retention window to `DEAD` with a WARN + a `*_outbox_dead_total` metric (media already has `media_outbox_dead_total`).

**Reproduction test.** Per service, an IT analogous to `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxRetentionIT.java:74-100`: seed a `FAILED` row with an old `failed_at`, run the retention scheduler, assert `status == DEAD` and the dead metric incremented; and seed a young `FAILED` row, assert it is replayed to `SENT`.

**Acceptance criterion.** Every outbox table in the four event-producing services has `claimed_at` and `failed_at`; every retention scheduler ages `FAILED` → `DEAD` with a metric; a `FAILED` row is either replayed or explicitly parked — never abandoned.

---

## CAT-11 — No concurrency admission control on the media decode path

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | media-service |
| **Evidence** | `media-service/src/main/java/com/shop/mediaservice/service/impls/VariantRenderer.java` (the decoded-bytes budget); `media-service/src/main/resources/application.yml:71-81` (`max-decoded-bytes: 64MB`); a sweep of `media-service/src/main/java` for `Semaphore|Limiter|permits` returns **0** hits |

**Actual behaviour.** The decoded-memory guard is **per request**: `media.max-decoded-bytes` (64 MB) bounds one upload's `width × height × 4` plus the source bytes. There is **no** semaphore, admission controller or bulkhead anywhere in media-service's main sources. N concurrent uploads therefore admit N × 64 MB of decoded pixels simultaneously, plus N × (source + 6 rendered outputs).

**Violated invariant.** *"The service's heap usage must be bounded regardless of concurrency."* The per-request budget bounds one request, not the service.

**Root cause.** The budget replaced the old *pixel* guard (8192 × 8192 = 268 MB per upload), whose own javadoc records the real failure mode: "concurrent uploads turned that into a heap-exhaustion outage instead of a rejected upload" (`VariantRendererMemoryBudgetTest.java:17-21`). The fix addressed the per-request magnitude but not the concurrency multiplier.

**Impact.** A burst of concurrent uploads can still exhaust the heap. The budget merely moves the threshold: with a 512 MB heap and a 64 MB budget, ~8 concurrent max-size uploads are enough. There is no `429`/`503` — the failure mode is a JVM `OutOfMemoryError`, not a clean rejection.

**Proposed fix.** Add an admission bulkhead in front of the decode: a `Semaphore` whose permits are derived from `max-decoded-bytes` and the heap size, acquired with a bounded `tryAcquire` before `renderer.render(...)` and released in a `finally`. On exhaustion, reject with `MEDIA_STORAGE_UNAVAILABLE` (503) and a `Retry-After`.

**Reproduction test.** `media-service/src/test/java/com/shop/mediaservice/controller/MediaUploadMemoryBudgetIT.java` — a new test that fires N concurrent uploads with `media.max-decoded-bytes` set to a value that admits only K at a time, and asserts that requests beyond K receive a 503/429 rather than triggering an OOM or a long stall.

**Acceptance criterion.** The decode path is guarded by a bounded semaphore; exceeding the limit yields a clean HTTP rejection; a concurrency test proves the service's heap stays bounded under N parallel max-size uploads.

---

## CAT-12 — 7-day presigned-URL TTL for catalogue images

| | |
|---|---|
| **Severity** | LOW |
| **Service** | media-service |
| **Evidence** | `media-service/src/main/resources/application.yml:71-81` (`media.presign-ttl: 7d`); `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaQueryServiceImpl.java:40-64` |

**Actual behaviour.** Every read of a media asset issues a presigned GET URL valid for **7 days**. The URL is a bearer capability: whoever holds it can read the object for that period.

**Violated invariant.** *"A capability URL's lifetime should be bounded by the shortest useful caching window."* The catalogue's own cache TTL for product data is 10 minutes (`product-service/.../config/CacheConfig.java:62-68`); a 7-day URL is ~1000× longer.

**Root cause.** The default was chosen for CDN/browser-cache friendliness rather than for capability minimisation. It is configurable (`media.presign-ttl`), so this is a **default-value** judgement, not a hard-coded flaw.

**Impact.** Low. The objects are catalogue images (not secrets), the bucket ACL is asserted private at startup (CAT-15), and the URL is only issued to an authenticated caller. A leaked URL stays usable for up to 7 days.

**Proposed fix.** Lower the default to 1 h and let a deployment raise it; or, better, have the client request a short TTL and have the server renew it on demand (the read endpoint is already hit per catalogue refresh).

**Reproduction test.** `media-service/src/test/java/com/shop/mediaservice/storage/ProvisioningIT.java:51-58` — assert `mediaProperties.presignTtl()` equals the intended default; the existing assertion (7 days) must be updated deliberately.

**Acceptance criterion.** `media.presign-ttl` default ≤ 1 h; the provisioning IT asserts the new value; deployments that need longer set it explicitly.

---

## CAT-13 — No Elasticsearch mapping-version gate

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | search-service |
| **Evidence** | `search-service/src/main/java/com/shop/searchservice/search/IndexProvisioner.java:38-56` |

```java
38:         if (!client.indices().existsIndexTemplate(t -> t.name(INDEX_TEMPLATE)).value()) {
39:             client.indices().putIndexTemplate(t -> t …);
…
48:         if (!client.indices().exists(e -> e.index(INDEX)).value()) {
49:             client.indices().create(c -> c.index(INDEX));
…
53:         if (!client.indices().existsAlias(a -> a.name(ALIAS)).value()) {
54:             client.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
```

**Actual behaviour.** The template, the index and the alias are created **only if absent**. An existing `products-template` is **never updated**, so a mapping change (e.g. CAT-04's `price` → `scaled_float`) only takes effect on indices created *after* the change — and `ReindexServiceImpl.createIndex` (`:154-161`) creates `products-vN+1` **without an explicit mapping**, relying entirely on the template matching `products-v*` (`:41`). If the template is stale, every subsequent reindex silently inherits the stale mapping. There is no mapping-version key and no startup assertion that the live mapping matches the code's expectation.

**Violated invariant.** *"The live index mapping must match the code that writes it."*

**Root cause.** The provisioning is idempotent-by-existence-check rather than version-aware. This is the standard "declarative index management" gap.

**Impact.** A mapping change is a silent no-op for existing data and for the next reindex. The failure surfaces as wrong search behaviour (e.g. price filters off by a cent) with no error anywhere. Combined with CAT-04, fixing the mapping requires *also* replacing the template — which the current code will not do.

**Proposed fix.** (a) Add a `mapping_version` setting to the template and include it in the template name or as an index setting; on startup, compare the live template's version with the code's version and `PUT` the template when they differ (a template update is safe — it applies to indices created afterwards). (b) Pass the mapping explicitly in `ReindexServiceImpl.createIndex` so a reindex never depends on template state. (c) Add a startup assertion that the live alias target's mapping matches the expected fields.

**Reproduction test.** `search-service/src/test/java/com/shop/searchservice/search/SearchIndexProvisioningIT.java` — create a `products-template` with a stale mapping, run `provision()`, assert the live template was updated to the current mapping/version.

**Acceptance criterion.** `IndexProvisioner` reconciles the template to the code's declared mapping (not "create if absent"); `ReindexServiceImpl.createIndex` sets the mapping explicitly; a provisioning IT proves a stale template is corrected.

---

## CAT-14 — Unbounded `synchronized` in the search service token provider

| | |
|---|---|
| **Severity** | LOW |
| **Service** | search-service |
| **Evidence** | `search-service/src/main/java/com/shop/searchservice/security/ServiceTokenProvider.java:59` |

```java
59:     private synchronized String refreshToken() {
```

**Actual behaviour.** `refreshToken()` performs a synchronous HTTP POST to Keycloak (`:71-76`). While one thread holds the monitor, every other thread calling `getToken()` (`:50-57`) blocks for the **entire** duration of the HTTP call with **no timeout**.

**Violated invariant.** *"A cache-refresh lock must be bounded so a slow dependency cannot stall unrelated callers indefinitely."*

**Root cause.** search-service implemented the token cache before the `ReentrantLock.tryLock(5, SECONDS)` pattern was adopted. product-service (`security/ServiceTokenProvider.java:61-85`), media-service (`:61-85`) and rating-service (`:59-83`) all use the bounded form; search-service is the sole outlier.

**Impact.** The reindex path calls `getToken()` on every source-page fetch (`client/ProductBackofficeClient.java:45`). A hung Keycloak blocks the reindex thread **and** every other thread needing a token, with no upper bound. The double-checked caching around it is otherwise correct (`:51-55` outside, `:60-64` inside).

**Proposed fix.** Port the `ReentrantLock.tryLock(5, TimeUnit.SECONDS)` implementation from the sibling services verbatim.

**Reproduction test.** `search-service/src/test/java/com/shop/searchservice/client/ProductBackofficeClientTest.java` — a test where the token endpoint blocks for 10 s and a second caller must fail fast (or receive the still-valid cached token) rather than block for 10 s.

**Acceptance criterion.** `ServiceTokenProvider` uses the same bounded `tryLock` pattern as the other three services; the four implementations are textually equivalent modulo the properties type.

---

## CAT-15 — Bucket ACL is asserted once at startup only

| | |
|---|---|
| **Severity** | LOW |
| **Service** | media-service |
| **Evidence** | `media-service/src/main/java/com/shop/mediaservice/storage/BucketBootstrap.java:24-48` |

```java
24:     @Override
25:     public void run(final ApplicationArguments args) {
26:         try {
27:             bootstrap();
28:         } catch (IllegalStateException e) {
29:             throw e;
30:         } catch (Exception e) {
31:             log.error("Bucket bootstrap failed — media storage stays degraded until object storage is reachable", e);
32:         }
33:     }
34:
35:     void bootstrap() {
36:         storage.ensureBucketExists(properties.bucket());
37:         assertPrivateAcl(properties.bucket());
```

**Actual behaviour.** The ACL is checked **once**, at startup. A public grant is fatal (`IllegalStateException` re-thrown at `:28-29`; `BucketBootstrapTest.java:52-60` asserts this). A *read* failure is tolerated (the outer catch degrades; `BucketBootstrapTest.java:73-80`). **After startup, nothing re-checks the ACL.** A bucket whose ACL is changed to public by an operator, a console session, or a misconfigured IaC change is never detected by the service.

**Violated invariant.** *"A private-media bucket must be private for the lifetime of the service, not only at boot."*

**Root cause.** The assertion was implemented as a one-shot bootstrap check rather than as a periodic invariant.

**Impact.** Low in practice — S3/RustFS bucket policies are rarely changed at runtime — but a public-bucket regression is a **data-exposure** event, and the service has no mechanism to detect it. The tolerated-read-failure path compounds this: a boot-time ACL read outage means the check silently does not happen at all.

**Proposed fix.** Re-assert the ACL on a low-frequency schedule (e.g. daily, in the existing `MediaOutboxRetentionScheduler` slot or a dedicated `@Scheduled`), logging a WARN (not crashing) on a public grant so an alert fires without taking the service down. Additionally, consider making the check fail the boot when the ACL *read* fails, since a private bucket that cannot be verified should not be served silently.

**Reproduction test.** `media-service/src/test/java/com/shop/mediaservice/storage/BucketBootstrapTest.java` — a test that stubs a public ACL *after* a successful bootstrap and asserts the scheduled re-check raises the alarm.

**Acceptance criterion.** The private-ACL invariant is asserted periodically, not only at boot; a public grant detected after boot raises a WARN-level alert.

---

## CAT-16 — Reservation gates are an if/else chain

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | promotion-service |
| **Evidence** | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java:54-134` |

**Actual behaviour.** One 80-line method contains the lookup, the temporal-validity check, the idempotency check, the discount computation, three quota gates and the write. The order is documented as load-bearing (`:48-53`) and each gate is separately pinned by a test (`ReserveFlowIT.java:167-270`), but the chain can only be extended by editing the method.

**Violated invariant.** *"A business rule set should be independently testable and independently extensible."* The rules *are* independently testable (through the service), but not independently *composable*.

**Root cause.** No rule-abstraction was introduced; the gates were written inline. For six gates this is defensible; the method is now at the point where the cost of change is rising.

**Impact.** Adding a rule (e.g. "one redemption per customer email", "max redemptions per SKU") requires editing a 80-line transactional method and re-verifying the whole gate order. The risk is a subtle ordering regression: the gates are *not* commutative (e.g. PER_USER before MAX_REDEMPTIONS produces PRO-7006, the reverse produces PRO-7007 — a user-visible error-code change).

**Proposed fix.** The `ReservationGate` chain sketched in §11.2 — an ordered `List<ReservationGate>` where each gate is a `@Component` with an `order()`. The existing eight gate tests in `ReserveFlowIT.java:167-270` become the acceptance suite and must pass **unchanged** after the refactor, which proves the order is preserved.

**Reproduction test.** The existing `ReserveFlowIT` gate tests are the regression suite. Add one new test that injects an extra `ReservationGate` bean and asserts it participates in the chain at the declared order.

**Acceptance criterion.** `CampaignReservationServiceImpl.reserve()` contains no quota `if` statements; each gate is a separate class with its own test; all eight existing gate tests pass unmodified.

---

## CAT-17 — `FAILED` media outbox rows are re-published every 2 seconds for 7 days

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | media-service |
| **Evidence** | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaOutboxRelay.java:26`, `:54`, `:61`, `:139-153`; `media-service/src/main/java/com/shop/mediaservice/outbox/MediaOutboxRetentionScheduler.java:23`, `:30`, `:37` |

```java
26:     private static final List<OutboxStatus> DUE_STATUSES = List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);
…
54:     @Scheduled(fixedDelayString = "${shop.media.outbox.poll-millis:2000}", …)
…
61:                 Optional<OutboxEvent> locked = outboxRepo.claimOneDue(DUE_STATUSES);
…
142:             if (e.getRetryCount() >= maxRetries) {
143:                 e.setStatus(OutboxStatus.FAILED);
144:                 e.setFailedAt(Instant.now());
```

**Actual behaviour.** A row that exhausts `max-retries` (10) is parked as `FAILED`. Because `claimOneDue` selects `{PENDING, FAILED}` (`:26`, `:61`), the `FAILED` row is **re-claimed on the very next poll** — every **2 seconds**. It is only removed from the re-claim set when the retention scheduler ages it to `DEAD`, which happens **7 days** after `failed_at` (`MediaOutboxRetentionScheduler.java:23` `retention-days: 7`, `:30`, `:37`).

⇒ A permanently-broken media event is republished approximately **7 × 24 × 3600 / 2 = 302,400 times** over the window. `retryCount` is incremented on each failure (`:140`) with no cap, so the counter grows without bound.

**Violated invariant.** *"A failing publish must back off, and a permanently-failing event must reach a terminal state promptly."*

**Root cause.** The replay path was added ("FAILED is not terminal — it is replayed until the retention window ages it to DEAD", `MediaOutboxRelayTest.java:41-45`) without a **per-row delay**. `failed_at` is recorded but never used to *defer* the next attempt — only to age the row.

**Impact.** One permanently-broken row (e.g. a payload the broker rejects, or a topic that no longer exists) generates 2 publish attempts per second **for 7 days**, each a Kafka round trip and a DB claim+mark cycle. With several such rows the relay's claim budget (`batch-size: 100`) is consumed by retries, **delaying the delivery of healthy events** — a broken row starves the pipeline.

**Proof.** `MediaOutboxRelayTest.java:187-203` `relay_failedRowIsReplayedOnNextCycle` (a FAILED row is picked up again) and `:205-217` `relay_failedRowReplayFailure_staysFailed` (retryCount 10 → 11); `MediaOutboxIT.java:285-306` over real Kafka; `MediaOutboxRetentionIT.java:74-100` (the DEAD aging only after the 7-day window, "young FAILED keeps replaying").

**Note the mirror-image defect.** product/rating/promotion do the opposite — they **never** replay `FAILED` (CAT-10). The correct design is a middle ground: replay `FAILED` **with a per-row backoff**, and age to `DEAD` after a much shorter window than the retention purge.

**Proposed fix.** (a) Add a `next_attempt_at` column to `outbox_events`; on failure set `next_attempt_at = now + backoff(retryCount)` (e.g. `min(base × 5^(retryCount-1), 1h)`); (b) change `claimOneDue` to `WHERE status IN (PENDING, FAILED) AND (next_attempt_at IS NULL OR next_attempt_at <= now)`; (c) reduce the DEAD-aging window from the 7-day retention window to a dedicated, shorter `dead-after` (e.g. 24 h); (d) cap `retryCount`.

**Reproduction test.** `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxRelayTest.java` — a new test asserting that a `FAILED` row is **not** re-claimed before `next_attempt_at`, and that `next_attempt_at` grows with `retryCount`.

**Acceptance criterion.** A `FAILED` row's re-claim count per hour is bounded (e.g. ≤ 20); the backoff is monotonic and persisted; the DEAD-aging window is independent of, and shorter than, the SENT-purge retention window.

---

## CAT-18 — Inconsistent event-naming convention across producers

| | |
|---|---|
| **Severity** | INFORMATIONAL |
| **Service** | product-service, media-service, promotion-service, rating-service |
| **Evidence** | `product-service/.../service/impls/TransactionalProductEventPublisher.java:47,52,57` (`ProductCreated` / `ProductUpdated` / `ProductDeleted`); `media-service/.../outbox/MediaEventType.java` (`MediaCreated` / `MediaDeleted`); `promotion-service/.../service/impls/TransactionalPromotionEventPublisher.java` (`promotion.reserved.v1` / `promotion.committed.v1` / `promotion.released.v1`); `rating-service/.../service/RatingEventService.java:39` (`rating.submitted.v1`) |

**Actual behaviour.** Two conventions coexist: PascalCase (`ProductCreated`, `MediaCreated`) and dotted-lower-case with a version suffix (`promotion.reserved.v1`, `rating.submitted.v1`). The `eventType` is also used as the **Kafka switch key** in the consumers (`ProductSearchConsumer.java:55-60`, `MediaDeletedConsumer.java`), so a rename is a breaking wire change.

**Root cause.** Each service was built independently; the fleet has no shared event-naming standard in `utils/common-kafka` (only the topic names are centralised — `KafkaTopicContract`).

**Impact.** Low. Any cross-service event tooling (a schema registry, a replay tool, an audit pipeline) must handle both forms.

**Proposed fix.** Adopt the versioned dotted form fleet-wide (`product.created.v1`, `media.created.v1`) and centralise the event-type constants in `utils/common-kafka` next to `KafkaTopicContract`.

**Acceptance criterion.** A single naming convention; event-type constants live in the shared module.

---

## CAT-19 — The rating aggregate snapshot can be stale under concurrent reviews

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | rating-service (consumer impact in product-service) |
| **Evidence** | `rating-service/src/main/java/com/shop/ratingservice/service/RatingEventService.java:54-58`; `product-service/src/main/java/com/shop/productservice/service/ProductRatingService.java:25-38` |

```java
// RatingEventService.java:54-58
@Transactional(propagation = Propagation.REQUIRED)
public void record(Rating rating, RatingAction action) {
    Object[] aggregate = ratingRepository.findAggregateByProductId(rating.getProductId()).get(0);
    BigDecimal avgRating = toBigDecimal(aggregate[0]).setScale(2, RoundingMode.HALF_UP);
    int ratingCount = ((Number) aggregate[1]).intValue();
```

**Actual behaviour.** The aggregate is a live JPQL `avg`/`count` — **not** a racy read-modify-write of a stored counter, and that part is correct. But the snapshot is computed at `record()` time under Postgres's default `READ COMMITTED`, so two concurrent reviews for the **same product** each see only their own uncommitted row:
- T1 (rating 5) → snapshot `avg=5.00, count=1`
- T2 (rating 1) → snapshot `avg=1.00, count=1`   ← **T2 does not see T1's uncommitted row**

The last writer's snapshot is wrong (the truth is `avg=3.00, count=2`). product-service then copies that snapshot **verbatim** onto `products.avg_rating` / `products.rating_count` — `ProductRatingServiceTest.java:119-130` explicitly pins "copied verbatim, no action filtering".

**Violated invariant.** *"The denormalised aggregate published to downstream consumers must equal the current aggregate."*

**Root cause.** The snapshot is computed without any lock that serialises concurrent rating writes for the same product. The `saveAndFlush` discipline (`RatingServiceImpl.java:64-67`) guarantees the aggregate sees the *caller's own* row — it does not serialise *other* transactions.

**Impact.** The storefront's displayed rating can be wrong (both the value and the count) until the next rating event for that product arrives. The ratings themselves are never lost — only the published snapshot. The same staleness affects `ProductUpdated` re-publications, so the search index also carries the wrong `avgRating`/`ratingCount`.

**Proposed fix.** Serialise the snapshot computation per product: take a row lock on the product's newest rating row (`SELECT … FROM ratings WHERE product_id = ? ORDER BY created_at DESC LIMIT 1 FOR UPDATE`) inside `record()`, or maintain a dedicated `product_rating_stats` row with a `@Version` that the rating transaction updates. Alternatively, compute the aggregate in the consumer (product-service) rather than in the producer, and publish only the raw rating.

**Reproduction test.** `rating-service/src/test/java/com/shop/ratingservice/service/RatingEventServiceTest.java` — a test that inserts two ratings for one product in two concurrent transactions and asserts the *published* snapshot equals `avg=3.00, count=2`.

**Acceptance criterion.** Under concurrent reviews for one product, every published `avgRating`/`ratingCount` pair equals the post-commit aggregate.

---

## CAT-20 — `Rating.verified` is stored but never enforced on the read path

| | |
|---|---|
| **Severity** | LOW |
| **Service** | rating-service |
| **Evidence** | `rating-service/src/main/java/com/shop/ratingservice/entity/Rating.java:47`; `rating-service/src/main/java/com/shop/ratingservice/service/impls/RatingServiceImpl.java:80` |

```java
// RatingServiceImpl.java:80
return ratingRepository.findByProductIdAndHiddenFalseAndDeletedFalse(productId, pageRequest)
```

**Actual behaviour.** The `verified` flag is written at submit time (`:60` `verified(eligible)`), carried in the event payload (`RatingEventService.java:79`), and **never read** on the storefront read path — the filter is `hidden = false AND deleted = false` only. A rating with `verified = false` (from a pre-verification era, a migration, or a data-fix) is displayed as a normal review.

**Violated invariant.** *"A verified-buyer-only rating surface must not display unverified ratings."*

**Root cause.** The `verified` flag was added as a *display/analytics* attribute rather than as a filter predicate.

**Impact.** Low, because the write path can only produce `verified = true` today (`RatingServiceImpl.java:49-52` refuses to insert when not eligible). The risk is a data-quality drift that the read path will not surface.

**Proposed fix.** Decide the intent explicitly: either filter on `verified = true` in the storefront query, or drop the column. If it is kept for analytics only, document that in the entity javadoc.

**Reproduction test.** `rating-service/src/test/java/com/shop/ratingservice/service/impls/RatingServiceImplTest.java` — seed a `verified = false` rating and assert the intended visibility.

**Acceptance criterion.** `verified` is either enforced by the read query or explicitly documented as informational.

---

## CAT-21 — Tax rounding regime is neither documented nor configurable

| | |
|---|---|
| **Severity** | LOW |
| **Service** | tax-service |
| **Evidence** | `tax-service/src/main/java/com/shop/taxservice/service/TaxCalculator.java:13-16` |

```java
13:     public static TaxCalculateResponse calculate(BigDecimal amount, BigDecimal ratePct) {
14:         BigDecimal taxAmount = amount.multiply(ratePct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
```

**Actual behaviour.** The scale (2) and the rounding mode (HALF_UP) are hard-coded in a static method. Many jurisdictions mandate HALF_EVEN (banker's rounding) or a round-*up* rule for VAT. The choice is correct as a default but is nowhere stated as a platform promise, and cannot be changed without a code change.

**Violated invariant.** *"A pricing calculation's rounding regime is part of the published contract."*

**Root cause.** The rounding regime was baked into a static helper instead of being a named, configurable policy.

**Impact.** Low. The behaviour is pinned by `TaxCalculatorTest`, so a change is deliberate. But an order-service integrating with tax-service cannot discover the regime from the contract.

**Proposed fix.** Name the regime explicitly (`RoundingMode.HALF_UP` → a `TaxRoundingPolicy` enum with `HALF_UP`, `HALF_EVEN`, `CEILING`), make it configurable per tax class, and document it in the endpoint contract.

**Reproduction test.** `tax-service/src/test/java/com/shop/taxservice/service/TaxCalculatorTest.java` — extend to cover the alternative regimes.

**Acceptance criterion.** The rounding regime is a named, configurable policy and is stated in the tax-service API contract.

---

## CAT-22 — The pricing contract has no net/gross declaration

| | |
|---|---|
| **Severity** | LOW |
| **Service** | tax-service |
| **Evidence** | `tax-service/src/main/java/com/shop/taxservice/dto/request/TaxCalculateRequest.java`; `tax-service/src/main/java/com/shop/taxservice/dto/response/TaxCalculateResponse.java` |

**Actual behaviour.** The request carries an amount, a tax class, a country and a postal code; the response carries the tax amount and the rate. **Neither side declares whether the input amount is tax-inclusive or tax-exclusive**, and neither carries a currency (CAT-05).

**Violated invariant.** *"A tax calculation must state the basis on which the input amount is expressed."*

**Root cause.** The contract was modelled as "apply a percentage to an amount" rather than as "convert between a net and a gross basis".

**Impact.** Low today (a single caller, order-service, which is also the only permitted caller — `TaxCalculationController.java:30` `requireCaller("order-service")`). It becomes a live ambiguity the moment a second caller appears or order-service changes its internal representation.

**Proposed fix.** Add an explicit `priceBasis` enum (`NET` / `GROSS`) to the request and the response, and reject a request that omits it.

**Reproduction test.** `tax-service/src/test/java/com/shop/taxservice/controller/TaxCalculationControllerTest.java` — assert the basis is required and echoed.

**Acceptance criterion.** Both the request and the response carry an explicit price basis.

---

## CAT-23 — No retention purge for the `notifications` table

| | |
|---|---|
| **Severity** | MEDIUM |
| **Service** | notification-service |
| **Evidence** | `notification-service/src/main/java/com/shop/notificationservice/scheduler/NotificationRetryScheduler.java:67` — the **only** `@Scheduled` in the service; no `NotificationRetentionScheduler` exists |

**Actual behaviour.** The `notifications` table accumulates one row per order lifecycle event, forever. Terminal rows (`SENT`, `FAILED_PERMANENT`, `SKIPPED`) are never deleted. The service has a durable, well-engineered retry path but no lifecycle management for its own domain table.

**Violated invariant.** *"Every event-producing service purges its own terminal rows after a retention window."* This is the fleet-wide convention — and it is documented as such in each of the outbox retention schedulers' javadocs, e.g. `product-service/.../service/OutboxRetentionScheduler.java:11-18`: "Every other event-producing service in the fleet … purges SENT rows after seven days; this service was the outlier."

**Root cause.** The retry work (changelog-002/003) added the durable retry machinery but not the retention pass.

**Impact.** Unbounded table growth on the highest-volume table in the service. Over a year this is millions of rows, degrading the `(status, next_retry_at)` index and every query against the table. There is also no way to purge a user's notification history for GDPR purposes.

**Proposed fix.** Add a `@Scheduled` retention pass purging `SENT` rows older than N days (default 30, configurable), and a separate, operator-triggered purge for a user's notifications.

**Reproduction test.** `notification-service/src/test/java/com/shop/notificationservice/scheduler/NotificationRetryIT.java` — a test that seeds a terminal row with an old timestamp, runs the retention pass, and asserts the row is gone while a recent one survives.

**Acceptance criterion.** A retention scheduler deletes terminal notification rows past the configured window; a test proves old terminal rows are purged and recent ones are kept.

---

## CAT-24 — `@Value`-injected configuration fields are non-final on singletons

| | |
|---|---|
| **Severity** | LOW |
| **Service** | product-service, media-service, promotion-service, rating-service |
| **Evidence** | `product-service/.../service/OutboxRelay.java:76,79,82,90`; `media-service/.../outbox/MediaOutboxRelay.java:77,80,83,91`; `media-service/.../outbox/MediaOutboxRetentionScheduler.java:24`; `promotion-service/.../service/PromotionOutboxRelay.java:72,75,78,86`; `promotion-service/.../service/ReservationCleanupScheduler.java:44,47`; `promotion-service/.../service/impls/CampaignReservationServiceImpl.java:46`; `rating-service/.../outbox/RatingOutboxRelay.java:66,69,72,80` |

**Actual behaviour.** Thirteen configuration fields are injected via `@Value` into **mutable** instance fields of singleton beans. They are written once by the container and read thereafter, so under Spring's publication semantics they are safe — but the immutability is conventional, not structural.

**Violated invariant.** *"A singleton's configuration must be immutable after construction."*

**Root cause.** Field injection with `@Value` (as opposed to constructor injection with `final` fields) — the pattern that also produced CAT-03's `currentPage`.

**Impact.** Low. A future in-place write (or a subclass in a test that mutates the field, as `OutboxRelayTest.java:71-74` and `MediaOutboxRelayTest.java:75-78` do via `ReflectionTestUtils.setField`) would silently corrupt a shared singleton.

**Proposed fix.** Convert to constructor injection with `final` fields, matching the `NotificationServiceImpl.java:40-53` pattern already used in notification-service.

**Reproduction test.** Not applicable (a structural cleanup). A reflection-based assertion test can verify the fields are `final`.

**Acceptance criterion.** No non-`final`, non-`static` instance field on any singleton bean in the eight services, except the `ProductMediaSweepJob.currentPage` field until CAT-03 is fixed.

---

## CAT-25 — `CouponUsageReservation.@Version` is hand-bumped inside a JPQL string

| | |
|---|---|
| **Severity** | LOW |
| **Service** | promotion-service |
| **Evidence** | `promotion-service/src/main/java/com/shop/promotionservice/repository/CouponUsageReservationRepository.java:68-74` vs `promotion-service/src/main/java/com/shop/promotionservice/entity/CouponUsageReservation.java:73-76` |

```java
// CouponUsageReservationRepository.java:68-74
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE CouponUsageReservation r SET r.status = :expired, r.releasedAt = :now, r.version = r.version + 1 "
     + "WHERE r.id = :id AND r.status = :pending")
int claimForExpiry(...);
```

**Actual behaviour.** The `@Version` column has two owners: Hibernate (which manages it for entity writes) and the hand-written JPQL bulk UPDATE (which bumps it manually). Hibernate's bulk-update path does **not** apply the version check — the JPQL is a raw SQL-level update that bypasses it — so the manual `r.version = r.version + 1` is what keeps the entity's in-memory version consistent with the row.

**Violated invariant.** *"The optimistic-locking version has exactly one owner."*

**Root cause.** A bulk JPQL UPDATE was needed for the conditional claim (`WHERE r.status = :pending`), and the version bump was added by hand to preserve the optimistic-locking contract — a reasonable trade-off, but an undocumented one.

**Impact.** Low. The manual bump is correct today. The risk is drift: if the entity's `@Version` mapping or the claim's WHERE clause changes, the hand-written increment can be forgotten, and the row's version would then be out of step with the entity's, producing spurious or missing `OptimisticLockingFailureException`s.

**Proposed fix.** Document the coupling in the entity javadoc (`CouponUsageReservation.java:73-76`) and in the repository method javadoc (`CouponUsageReservationRepository.java:53-67`); add an IT that asserts the version is incremented exactly once per claim. Long term, replace the bulk JPQL with a native `UPDATE … RETURNING` or a pessimistic-lock read-modify-write so Hibernate owns the version.

**Reproduction test.** `promotion-service/src/test/java/com/shop/promotionservice/service/ReservationCleanupSchedulerTest.java` — assert the version is incremented by exactly 1 per successful `claimForExpiry` and by 0 for a losing claimer.

**Acceptance criterion.** The version-bump coupling is documented and covered by a test; a future change to the claim query cannot silently break optimistic locking.

---

# Appendix A — Verified-correct behaviours (credit)

The following were checked and found **correct**. They are recorded so a later reader does not re-open them as findings.

| # | Behaviour | Evidence |
|---|---|---|
| A-1 | The product outbox insert is in the **same transaction** as the business write | `TransactionalProductEventPublisher.java:99` + the caller's `@Transactional`; proven by `ProductRatingOutboxIntegrationTest.java:35-74` and `OutboxRelayIntegrationTest.java:59-61` |
| A-2 | The outbox claim uses `FOR UPDATE SKIP LOCKED`; a concurrent claimer gets empty, never the same row | `OutboxEventRepository.java:35-39`; `ProductOutboxClaimConcurrencyIT.java:56-185` |
| A-3 | The outbox mark phase is a compare-and-set under the row lock | `OutboxEventRepository.java:53-56` |
| A-4 | Stranded `SENDING` rows are reclaimed by the **relay**, not by a stray method | `OutboxRelay.java:191`; `OutboxRelayRecoveryIT.java:68-85` |
| A-5 | The relay publishes per-aggregate in ascending id order and blocks only the same aggregate's later records | `OutboxRelay.java:245`; `OutboxRelayTest.java:143-168` |
| A-6 | The media HEAD check runs **outside** the transaction | `ProductServiceImplTransactionBoundaryIT.java:44-49` |
| A-7 | The rating eligibility check runs **outside** the transaction | `RatingServiceImplTransactionBoundaryIT` |
| A-8 | Every safety-critical remote call is **fail-closed** | `MediaHeadClient.java:47-62`; `InventoryReservationClient.java:53-71`; `MediaReferenceClient.java:56-75`; `EligibilityClient.java:50-69`; `ProductMediaReferenceChecker.java:24-35` |
| A-9 | A 404 from inventory-service is treated as UNKNOWN, not as "no reservation" | `InventoryReservationClientTest.java:105-113` |
| A-10 | The declared Content-Type is **not trusted**; magic bytes must agree | `MediaUploadServiceImpl.java:70-76`; `MediaFormats.java:54-69`; `UploadIT.java:212-228` |
| A-11 | The decoded-memory budget is checked **before** the decode | `VariantRendererMemoryBudgetTest.java:51-60`; `MediaUploadMemoryBudgetIT.java:74-88` |
| A-12 | EXIF/GPS metadata is stripped by the re-encode | `UploadIT.java:255-276` |
| A-13 | Stored objects are always a re-encode, never the raw upload | `UploadIT.java:164-177` |
| A-14 | The object key is built only from a server-generated UUID + a fixed variant + a fixed extension ⇒ **no path traversal** | `MediaUploadServiceImpl.java:224-226` |
| A-15 | Partial-upload failure purges every orphan object | `UploadIT.java:280-300`, `:359-374`; `MediaUploadServiceImplTest.java:113-129` |
| A-16 | The servlet multipart ceiling is derived from the same property as the business cap ⇒ no drift | `UploadLimitsConfig.java:21-31`; `ProvisioningIT.java:60-68` |
| A-17 | The media delete refuses when the product reference cannot be checked (fail-safe) | `MediaLifecycleServiceImpl.java:76-84`; `MediaLifecycleServiceImplTest.java:95-109` |
| A-18 | The purge deletes objects **before** rows and one failure never aborts the batch | `MediaPurgeJob.java:50-62`; `MediaPurgeJobTest.java:175-190` |
| A-19 | The ES document id is **stable** (the product UUID) ⇒ duplicates are idempotent | `ProductSearchService.java:45` |
| A-20 | Non-ACTIVE payloads **delete** the document, and a re-publish to ACTIVE re-upserts it | `ProductSearchService.java:37-40`; `ProductSearchIngestionIT.java:164-197` |
| A-21 | A delete of a never-indexed product is a clean no-op | `ProductSearchService.java:55-71`; `ProductSearchIngestionIT.java:211-222` |
| A-22 | The index template, index and alias are **app-created** and the provisioning is idempotent | `IndexProvisioner.java:37-57`; `SearchIndexProvisioningIT.java:18-38` |
| A-23 | The reindex aborts **without an alias swap** on any failure | `ReindexServiceImpl.java:26-31`, `:118-128` |
| A-24 | The reindex source explicitly requests `status=ACTIVE` ⇒ only sellable products are indexed | `ProductBackofficeClient.java:43` |
| A-25 | The alias swap is a single atomic `updateAliases` | `ReindexServiceImpl.java:176-186` |
| A-26 | The reindex lock correctly rejects a concurrent in-process run with 409 | `ReindexServiceImpl.java:53-55` |
| A-27 | The storefront list defaults to `status = ACTIVE` at the **service** layer (defence in depth) | `ProductServiceImpl.java:70-76`; `ProductServiceFilterSpecIT.java:43-79` |
| A-28 | The reservation TTL is server-side and the clock is injected | `CampaignReservationServiceImpl.java:43,45-46,119` |
| A-29 | The campaign window is evaluated server-side against the injected clock | `CampaignReservationServiceImpl.java:61-65` |
| A-30 | The expiry sweep uses a **conditional atomic UPDATE** — no lost update against a concurrent commit | `CouponUsageReservationRepository.java:68-74` |
| A-31 | The budget quota is returned when a hold expires | `ReserveFlowIT.java:332-373` |
| A-32 | Duplicate reviews are prevented by a **partial unique index** | `changelog-001-ratings.yaml:42` |
| A-33 | The aggregate rating is a live DB aggregate, not a racy stored counter | `RatingEventService.java:56`; `RatingRepository.java:22-24` |
| A-34 | The rating outbox **claim lease** is a dedicated `claimed_at` column | `changelog-002-rating-outbox-claim-lease.yaml`; `OutboxEventRepository.java:74-85` |
| A-35 | The rating outbox lease recovery is driven by the relay | `RatingOutboxRelayRecoveryIT.java` |
| A-36 | The notification retry is bounded (max 6), durable (DB state) and overflow-safe (exponent cap) | `NotificationRetryPolicy.java:18-47` |
| A-37 | The notification claim is pessimistic + heartbeat, and the send is outside any transaction | `NotificationRetryScheduler.java:96-111`; `NotificationServiceImpl.java:115-117` |
| A-38 | Notification dedupe is enforced by a unique index + a catch | `NotificationServiceImpl.java:61-64,80-85` |
| A-39 | Favourite user isolation is enforced **in SQL**, and the user id comes from the JWT | `FavouriteRepository.java:28,38-50,56-68`; `FavouriteController.java:65-73` |
| A-40 | Duplicate favourites are prevented by a partial unique index | `changelog-001:36-39` |
| A-41 | The tax rate resolution is specific-first (postal → country → class default) | `TaxCalculationServiceImpl.java:34-40`; `TaxRateRepository.java:18-23` |
| A-42 | The tax calculation uses an explicit scale and rounding mode on the divide | `TaxCalculator.java:14` |
| A-43 | The tax endpoint is SERVICE-gated to order-service only | `TaxCalculationController.java:30` |
| A-44 | The media bucket is read/written/purged through the bucket-qualified overloads only | `MediaBucketUnificationTest.java:112-113,136-137,152-153,168-169` |
| A-45 | The `Media` entity's `Persistable.isNew` flag is correctly reset by `@PostLoad`/`@PrePersist` | `Media.java:48-61` |
| A-46 | Every outbox relay marks each row by **its own** outcome, not the batch's | `OutboxRelay.java:206-222`; `MediaOutboxRelay.java:129-155` |
| A-47 | The `Media` outbox has the full ladder: `failed_at`, FAILED replay, and DEAD aging with a metric | `OutboxEventRepository.java:44,55-58`; `MediaOutboxRetentionScheduler.java:37-42` |
| A-48 | No `@Async` anywhere in the eight services — every background job is DB-coordinated | sweep of `src/main/java` for `@Async`: 0 hits |
| A-49 | Every mutable counter on a singleton is an `AtomicInteger` or a Micrometer meter | `ProductMetrics.java:41`; `PromotionMetrics.java:33`; `MediaMetrics`; `RatingMetrics` |
| A-50 | No mutable static fields in production code | sweep of `src/main/java` for non-`final` statics: 0 hits |

---

# Appendix B — Coverage attestation

- **430 / 430** files in scope read in full. **0** silent skips.
- **38,177** lines read (393 `.java` + 37 YAML/YML).
- Every file verified tracked at `a2b6e6fc336f42f851166cab0cf0d1bba42db12f` via `git rev-parse HEAD:<path>`.
- **No application source file was modified.** The only file written by this audit is this report.
- No build or test command was executed (the audit is source-only and read-only by instruction).
- **25 defects** raised: 4 HIGH (CAT-01, CAT-02, CAT-05, CAT-07), 12 MEDIUM, 8 LOW, 1 INFORMATIONAL.
- **50 verified-correct behaviours** recorded in Appendix A.
