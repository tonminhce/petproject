# Shared Libraries — Source Review (A1/A2: Java+Spring / DDD & Design Patterns)

| Field | Value |
| --- | --- |
| Repository | `/Users/tonminh-mac/IdeaProjects/untitled5` |
| Git revision | `a2b6e6fc336f42f851166cab0cf0d1bba42db12f` (verified via `git rev-parse HEAD`) |
| Scope | `utils/common-core`, `utils/common-kafka`, `utils/common-keycloak`, `utils/common-logging`, `utils/common-patterns`, `utils/common-security`, `utils/common-spring`, `utils/common-storage`, root `pom.xml`, `utils/pom.xml` |
| Java files read | **135** (90 `src/main`, 45 `src/test`) |
| Java lines read | **12 174** |
| POMs read | 9 (root + `utils/` + 7 module POMs) + 1 orphan flattened POM |
| Method | Full-file reads with the `read` tool; every file opened at line 1 and read to EOF |
| Verification | Source only. `README.md` and `docs/` were **not** used as evidence. Cross-module facts were confirmed with `grep`/git, never assumed. |
| Writing policy | Only this Markdown report was written. No application source file was modified. |

## 0. Scope caveat — `utils/common-patterns` does not exist

The task brief listed `utils/common-patterns` and stated it "has 0 Java files — verify what it actually contains". The honest finding is stronger than that:

- `utils/common-patterns` **contains no `pom.xml`** and **is not listed** in the `<modules>` of `utils/pom.xml` (lines 18–26). It is therefore **not a Maven module** and is not built, tested, or published by this reactor.
- `git ls-files utils/common-patterns/` returns **0 tracked files** — the directory has never been committed at this revision.
- `git log -- utils/common-patterns/` shows the module was **removed** by commit `5d6ba25` *"feat(quality-gate): replace common-patterns with Checkstyle and SonarQube"* (preceded by `74ac143` which introduced it).
- The only residue on disk is **untracked build output**: `utils/common-patterns/.flattened-pom.xml` (45 lines, a flattened POM whose `<artifactId>common-patterns</artifactId>` and `javaparser-core` test dependency describe the deleted compliance-harness module) and a stale `utils/common-patterns/target/` tree containing compiled `FleetPatternComplianceTest.class` files and Surefire reports.

**Verdict: `common-patterns` is a ghost module — a deleted artifact, not a resource-only module.** There is no Java source, no resource, no POM, and no build participation to review. The rules it used to enforce (R1–R6 fleet patterns) are now delegated to Checkstyle + SonarQube per the root `pom.xml` (lines 315–336, 387–392). No finding in this report is attributed to it.

---

## 1. Complete file inventory — every file read, with blob hash and line range

All 135 Java files were read **in full**: each was opened at line 1 and read to EOF, so the "lines read" column is `1–N` for every row. Blob hashes are `git rev-parse HEAD:<path>` (first 12 hex chars shown; full hashes available via the command). **No file in scope was skipped.**

### 1.1 `utils/common-core` — 20 files, 1 126 lines

| `utils/common-core/src/main/java/com/shop/common/core/audit/AuditActor.java` | `aea287f15b44` | 70 | 1–70 |
| `utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java` | `893cfce7c7a5` | 61 | 1–61 |
| `utils/common-core/src/main/java/com/shop/common/core/constants/MdcKey.java` | `4b5f49a08471` | 18 | 1–18 |
| `utils/common-core/src/main/java/com/shop/common/core/constants/OutboxStatus.java` | `02c98a69696c` | 21 | 1–21 |
| `utils/common-core/src/main/java/com/shop/common/core/constants/PageableConstant.java` | `336fe87159bc` | 14 | 1–14 |
| `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java` | `fa7d1aac90e3` | 46 | 1–46 |
| `utils/common-core/src/main/java/com/shop/common/core/data/SoftDeletable.java` | `1225eb759585` | 52 | 1–52 |
| `utils/common-core/src/main/java/com/shop/common/core/exception/BusinessException.java` | `502f4e6abc9e` | 118 | 1–118 |
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCatalog.java` | `1dd9fdd1e2f8` | 16 | 1–16 |
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | `e831125a7816` | 161 | 1–161 |
| `utils/common-core/src/main/java/com/shop/common/core/i18n/Messages.java` | `e63ae6aed092` | 74 | 1–74 |
| `utils/common-core/src/main/java/com/shop/common/core/package-info.java` | `bad2ca8cd6f9` | 10 | 1–10 |
| `utils/common-core/src/main/java/com/shop/common/core/util/DateTimeUtils.java` | `dea367533566` | 36 | 1–36 |
| `utils/common-core/src/main/java/com/shop/common/core/util/MoneyUtils.java` | `f39011547f11` | 84 | 1–84 |
| `utils/common-core/src/main/java/com/shop/common/core/viewmodel/ApiResponse.java` | `22e54db95b92` | 64 | 1–64 |
| `utils/common-core/src/main/java/com/shop/common/core/viewmodel/PageResponse.java` | `415f3aec5311` | 48 | 1–48 |
| `utils/common-core/src/test/java/com/shop/common/core/audit/AuditActorTest.java` | `b2c78ac3b925` | 50 | 1–50 |
| `utils/common-core/src/test/java/com/shop/common/core/data/AbstractMappedEntityTest.java` | `b1d58bffa701` | 62 | 1–62 |
| `utils/common-core/src/test/java/com/shop/common/core/exception/BusinessExceptionTest.java` | `fd1faa7abcca` | 55 | 1–55 |
| `utils/common-core/src/test/java/com/shop/common/core/util/MoneyUtilsTest.java` | `74aaa1478d73` | 66 | 1–66 |

### 1.2 `utils/common-kafka` — 25 files, 3 043 lines

| `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java` | `b2ae406e8ddf` | 103 | 1–103 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java` | `18a700dd1700` | 246 | 1–246 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java` | `bf45c30d1e21` | 118 | 1–118 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java` | `c6d4cbee91b0` | 113 | 1–113 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/PoisonRecordException.java` | `e81602785fb5` | 8 | 1–8 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/contract/KafkaTopicContract.java` | `0dad22d91556` | 148 | 1–148 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/exception/KafkaPublishException.java` | `fbd2fdd4be1e` | 15 | 1–15 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/package-info.java` | `830952b3ae87` | 7 | 1–7 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/producer/KafkaMessagePublisher.java` | `48e5423bb2bb` | 276 | 1–276 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/producer/OutboxBatchOrdering.java` | `fa87a8bcec55` | 58 | 1–58 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayEndpoint.java` | `5267be97bfee` | 52 | 1–52 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayService.java` | `96f6350d4b26` | 145 | 1–145 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/serialization/JsonKafkaDeserializer.java` | `19a00c040c4e` | 67 | 1–67 |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/serialization/JsonKafkaSerializer.java` | `4f9cc943d718` | 68 | 1–68 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/config/KafkaPropertiesProducerDefaultsTest.java` | `d392dc6ef855` | 54 | 1–54 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/config/KafkaPropertiesTopicsRemovedTest.java` | `4b34d87aba04` | 105 | 1–105 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaConsumerDeadLetterTest.java` | `fbe72e0b2d6f` | 175 | 1–175 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaConsumerTypedBindTest.java` | `4df867625cc7` | 100 | 1–100 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfigWireTest.java` | `c87656440f79` | 126 | 1–126 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/KafkaListenerGroupIdContractTest.java` | `bd714cd1ed3f` | 116 | 1–116 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/TraceparentHeaderExtractionTest.java` | `13147c3c3354` | 229 | 1–229 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/contract/KafkaTopicContractTest.java` | `efbc1cfa6cfa` | 260 | 1–260 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/producer/KafkaMessagePublisherTest.java` | `1d0d5b77abbf` | 156 | 1–156 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/producer/OutboxBatchOrderingTest.java` | `2a5284509d20` | 63 | 1–63 |
| `utils/common-kafka/src/test/java/com/shop/common/kafka/replay/DeadLetterReplayServiceTest.java` | `6bd009aedf65` | 235 | 1–235 |

### 1.3 `utils/common-keycloak` — 11 files, 1 926 lines

| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java` | `852942a8ba3b` | 599 | 1–599 |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java` | `c2f1a10c7128` | 179 | 1–179 |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java` | `6513991273bc` | 101 | 1–101 |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakProperties.java` | `9e9094d99ecf` | 116 | 1–116 |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/dto/KeycloakTokenResponse.java` | `c22271447bfd` | 17 | 1–17 |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/exception/KeycloakClientException.java` | `b44bac609371` | 26 | 1–26 |
| `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientGrantPostureTest.java` | `8cafda91366c` | 111 | 1–111 |
| `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientRoleMappingTest.java` | `e436cb5790d7` | 115 | 1–115 |
| `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientServiceAccountTest.java` | `964b159b0b1b` | 176 | 1–176 |
| `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/AdminServiceAccountRealmContractTest.java` | `c7326faaa478` | 217 | 1–217 |
| `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/RealmImportCredentialContractTest.java` | `50d3ad65a9c7` | 269 | 1–269 |

### 1.4 `utils/common-logging` — 22 files, 1 593 lines

| `utils/common-logging/src/main/java/com/shop/common/logging/LogField.java` | `d015f55d58c0` | 20 | 1–20 |
| `utils/common-logging/src/main/java/com/shop/common/logging/LogPerformance.java` | `9cca27a527a8` | 26 | 1–26 |
| `utils/common-logging/src/main/java/com/shop/common/logging/Loggable.java` | `e85631f830e9` | 21 | 1–21 |
| `utils/common-logging/src/main/java/com/shop/common/logging/aspect/AuditAspect.java` | `acb072cc9cff` | 71 | 1–71 |
| `utils/common-logging/src/main/java/com/shop/common/logging/aspect/LoggerAspect.java` | `634a6170542c` | 99 | 1–99 |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/AuditActorResolver.java` | `eb7d0a9e4450` | 81 | 1–81 |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/AuditEvent.java` | `3a281ece7a02` | 82 | 1–82 |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/AuditEventWriter.java` | `f63191288f22` | 21 | 1–21 |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/AuditResourceResolver.java` | `0c8ed27cc20c` | 46 | 1–46 |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/Audited.java` | `1f9a177a75c6` | 30 | 1–30 |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/BoundedAsyncAuditEventWriter.java` | `7c1b5e3cd0de` | 213 | 1–213 |
| `utils/common-logging/src/main/java/com/shop/common/logging/config/AuditAutoConfiguration.java` | `df3be1232dcc` | 45 | 1–45 |
| `utils/common-logging/src/main/java/com/shop/common/logging/config/AuditLogProperties.java` | `cfa834ed8b6c` | 24 | 1–24 |
| `utils/common-logging/src/main/java/com/shop/common/logging/config/LoggingAutoConfiguration.java` | `c70530677b37` | 31 | 1–31 |
| `utils/common-logging/src/main/java/com/shop/common/logging/config/PerformanceLogProperties.java` | `5d92f1bf7e72` | 36 | 1–36 |
| `utils/common-logging/src/main/java/com/shop/common/logging/package-info.java` | `84d255f9ccdc` | 11 | 1–11 |
| `utils/common-logging/src/test/java/com/shop/common/logging/aspect/AuditAspectTest.java` | `47f1eeb746fe` | 195 | 1–195 |
| `utils/common-logging/src/test/java/com/shop/common/logging/audit/AuditActorResolverTest.java` | `ded7e582b33e` | 120 | 1–120 |
| `utils/common-logging/src/test/java/com/shop/common/logging/audit/AuditEventTest.java` | `b0ef37afe73c` | 66 | 1–66 |
| `utils/common-logging/src/test/java/com/shop/common/logging/audit/AuditResourceResolverTest.java` | `c8f31df09255` | 69 | 1–69 |
| `utils/common-logging/src/test/java/com/shop/common/logging/audit/BoundedAsyncAuditEventWriterTest.java` | `9c68bf3e5fa5` | 213 | 1–213 |
| `utils/common-logging/src/test/java/com/shop/common/logging/config/AuditAutoConfigurationTest.java` | `eb9c2af03aaa` | 73 | 1–73 |

### 1.5 `utils/common-patterns` — 0 files, 0 lines

| _(no Java files — module removed in commit `5d6ba25`; see section 0)_ | — | 0 | — |

### 1.6 `utils/common-security` — 15 files, 1 177 lines

| `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java` | `1d0aeef38122` | 158 | 1–158 |
| `utils/common-security/src/main/java/com/shop/common/security/config/CorsAutoConfigurer.java` | `ef73b28ca0e1` | 66 | 1–66 |
| `utils/common-security/src/main/java/com/shop/common/security/config/LazyJwtDecoder.java` | `9efbbe5ef1d5` | 95 | 1–95 |
| `utils/common-security/src/main/java/com/shop/common/security/config/SecurityAutoConfiguration.java` | `381e887a2d01` | 40 | 1–40 |
| `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java` | `2fe5f1944cad` | 161 | 1–161 |
| `utils/common-security/src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java` | `24068ee763b4` | 90 | 1–90 |
| `utils/common-security/src/main/java/com/shop/common/security/jwt/JwtClaimExtractor.java` | `98f30ae41c65` | 81 | 1–81 |
| `utils/common-security/src/main/java/com/shop/common/security/jwt/JwtRolesConverter.java` | `60e6d4314716` | 64 | 1–64 |
| `utils/common-security/src/main/java/com/shop/common/security/package-info.java` | `ad4324ed2483` | 6 | 1–6 |
| `utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java` | `c2b01d347476` | 68 | 1–68 |
| `utils/common-security/src/test/java/com/shop/common/security/config/BaseSecurityConfigAudienceValidationTest.java` | `5cb750770eb4` | 72 | 1–72 |
| `utils/common-security/src/test/java/com/shop/common/security/config/BaseSecurityConfigClientIdValidationTest.java` | `72055108df9a` | 67 | 1–67 |
| `utils/common-security/src/test/java/com/shop/common/security/config/BaseSecurityConfigJwtDecoderLazyTest.java` | `c4953d84740a` | 62 | 1–62 |
| `utils/common-security/src/test/java/com/shop/common/security/config/SecurityPropertiesCorsMaxAgeTest.java` | `86f8ba8376d1` | 83 | 1–83 |
| `utils/common-security/src/test/java/com/shop/common/security/config/SecurityPropertiesTest.java` | `fca881966b43` | 64 | 1–64 |

### 1.7 `utils/common-spring` — 32 files, 2 524 lines

| `utils/common-spring/src/main/java/com/shop/common/spring/CommonLibraryStarter.java` | `824fb436395a` | 33 | 1–33 |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/I18nAutoConfiguration.java` | `b20087841314` | 65 | 1–65 |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/Jackson2ObjectMapperAutoConfiguration.java` | `427118f47eba` | 30 | 1–30 |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java` | `5458c2e290c9` | 77 | 1–77 |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/ModelMapperAutoConfiguration.java` | `4c31d397f7af` | 35 | 1–35 |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/TracingAutoConfiguration.java` | `317facb3a781` | 138 | 1–138 |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/WebAutoConfiguration.java` | `a4228e917780` | 50 | 1–50 |
| `utils/common-spring/src/main/java/com/shop/common/spring/config/CommonProperties.java` | `7abbc26f5558` | 164 | 1–164 |
| `utils/common-spring/src/main/java/com/shop/common/spring/mapper/BaseMapper.java` | `af0e08000b3f` | 14 | 1–14 |
| `utils/common-spring/src/main/java/com/shop/common/spring/mapper/EntityCreateUpdateMapper.java` | `ad8cbe0e0cbe` | 14 | 1–14 |
| `utils/common-spring/src/main/java/com/shop/common/spring/mapping/RecordValueReader.java` | `82cee3d8c7ca` | 63 | 1–63 |
| `utils/common-spring/src/main/java/com/shop/common/spring/package-info.java` | `94a4606e0d80` | 4 | 1–4 |
| `utils/common-spring/src/main/java/com/shop/common/spring/tracing/MdcOtelCurrentTraceContext.java` | `43f37bf8f52e` | 46 | 1–46 |
| `utils/common-spring/src/main/java/com/shop/common/spring/tracing/TraceparentInterceptor.java` | `66838d40bbcd` | 36 | 1–36 |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/exception/ApiExceptionHandler.java` | `3d175e0d84bc` | 337 | 1–337 |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/CorrelationIdFilter.java` | `894c82a9d498` | 64 | 1–64 |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/HttpLogProperties.java` | `3ee21724ffb0` | 58 | 1–58 |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java` | `991a7f8a40af` | 145 | 1–145 |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/i18n/I18nProperties.java` | `7f3417bcea5f` | 55 | 1–55 |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/util/RequestUtils.java` | `b897e1117f2c` | 45 | 1–45 |
| `utils/common-spring/src/test/java/com/shop/common/spring/CommonLibraryStarterTests.java` | `04c1ede9c1e9` | 77 | 1–77 |
| `utils/common-spring/src/test/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfigurationTest.java` | `89f013c82b6b` | 79 | 1–79 |
| `utils/common-spring/src/test/java/com/shop/common/spring/autoconfigure/ModelMapperAutoConfigurationTest.java` | `1cb1fe542c15` | 35 | 1–35 |
| `utils/common-spring/src/test/java/com/shop/common/spring/autoconfigure/TracingAutoConfigurationTest.java` | `74a205b260cc` | 214 | 1–214 |
| `utils/common-spring/src/test/java/com/shop/common/spring/deploy/ProductionEnvTemplateTest.java` | `62f9b45f9e97` | 142 | 1–142 |
| `utils/common-spring/src/test/java/com/shop/common/spring/mapping/RecordValueReaderTest.java` | `d97e0310d05e` | 121 | 1–121 |
| `utils/common-spring/src/test/java/com/shop/common/spring/test/TestSecurityConfig.java` | `0f43014fccb3` | 31 | 1–31 |
| `utils/common-spring/src/test/java/com/shop/common/spring/tracing/TraceparentInterceptorTest.java` | `e765f84a562e` | 86 | 1–86 |
| `utils/common-spring/src/test/java/com/shop/common/spring/web/ApiExceptionHandlerDataIntegrityTest.java` | `0c444adaa890` | 54 | 1–54 |
| `utils/common-spring/src/test/java/com/shop/common/spring/web/ServerErrorDefaultsTest.java` | `9e265858b17f` | 72 | 1–72 |
| `utils/common-spring/src/test/java/com/shop/common/spring/web/SharedDefaultsSecurityTest.java` | `741d975da6f4` | 63 | 1–63 |
| `utils/common-spring/src/test/java/com/shop/common/spring/web/exception/ApiExceptionHandlerConstraintLeakTest.java` | `075b88ad28eb` | 77 | 1–77 |

### 1.8 `utils/common-storage` — 10 files, 785 lines

| `utils/common-storage/src/main/java/com/shop/common/storage/client/S3ClientFactory.java` | `82964fd644a6` | 50 | 1–50 |
| `utils/common-storage/src/main/java/com/shop/common/storage/config/ObjectStorageAutoConfiguration.java` | `1be851c86243` | 73 | 1–73 |
| `utils/common-storage/src/main/java/com/shop/common/storage/config/StorageProperties.java` | `c7037ea2fee5` | 142 | 1–142 |
| `utils/common-storage/src/main/java/com/shop/common/storage/exception/StorageException.java` | `8cb68efa84ae` | 15 | 1–15 |
| `utils/common-storage/src/main/java/com/shop/common/storage/package-info.java` | `8b7b1335d371` | 11 | 1–11 |
| `utils/common-storage/src/main/java/com/shop/common/storage/service/ObjectStorageService.java` | `e20c67f22e01` | 65 | 1–65 |
| `utils/common-storage/src/main/java/com/shop/common/storage/service/S3ObjectStorageService.java` | `a9d55c1a71a2` | 215 | 1–215 |
| `utils/common-storage/src/main/java/com/shop/common/storage/service/StorageObject.java` | `4f3f6060a33a` | 29 | 1–29 |
| `utils/common-storage/src/test/java/com/shop/common/storage/config/StoragePropertiesCredentialsTest.java` | `5996f20816ba` | 68 | 1–68 |
| `utils/common-storage/src/test/java/com/shop/common/storage/service/S3ObjectStorageServiceDownloadLeakTest.java` | `c6c4517a835e` | 117 | 1–117 |


### 1.9 POM inventory (also read in full)

| Path | Blob hash | Lines | Lines read |
| --- | --- | --- | --- |
| `pom.xml` | `fa2d99d18ec4` | 554 | 1–554 |
| `utils/pom.xml` | `8eeda7f9463f` | 27 | 1–27 |
| `utils/common-core/pom.xml` | `8e465bc4cf4e` | 93 | 1–93 |
| `utils/common-kafka/pom.xml` | `d0051598c324` | 129 | 1–129 |
| `utils/common-keycloak/pom.xml` | `9fa954f51247` | 96 | 1–96 |
| `utils/common-logging/pom.xml` | `e6e629defd11` | 98 | 1–98 |
| `utils/common-security/pom.xml` | `9aaecaab3da2` | 75 | 1–75 |
| `utils/common-spring/pom.xml` | `4809ad116fac` | 191 | 1–191 |
| `utils/common-storage/pom.xml` | `848ffd10320b` | 75 | 1–75 |
| `utils/common-patterns/.flattened-pom.xml` | **NOT TRACKED** (orphan build output) | 45 | 1–45 |

Also read (not Java, not POM, but required for correctness of the findings below): `utils/common-spring/src/main/resources/application.yml` (261 lines, blob `—`/read 1–261), and the six `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` files (5 lines each).

---

## 2. Real responsibilities, public API surface, and the true dependency graph

### 2.1 Actual dependency graph (derived from the POMs, not from README claims)

```
                                   root pom.xml  (shop-microservices, packaging=pom, Java 25, Boot 4.1.1)
                                        │
                                   utils/pom.xml  (packaging=pom, aggregator)
                                        │
        ┌───────────────┬───────────────┼───────────────┬───────────────┐
        ▼               ▼               ▼               ▼               ▼
  common-core    common-security   common-logging  common-keycloak   common-kafka   common-storage
  (no internal   → common-core     → common-core    → common-core    (NO internal   (NO internal
   deps)                            (optional:      (spring-web,      deps at all)   deps at all)
                                    spring-security,                  │
                                    oauth2-jose,                     │
                                    spring-web,                      │
                                    spring-webmvc)                   │
        ▲                                ▲                            │
        └────────────────────────────────┘                            │
        │                                                            │
        └──────────────────── common-spring ◄────────────────────────┘
                            (→ core, security, logging, keycloak,
                             kafka [optional], storage [optional])
```

**Measured facts from the POMs:**

| Module | Internal `com.shop.microservices` deps | Heavy external deps |
| --- | --- | --- |
| `common-core` | **none** | `spring-boot-starter-data-jpa` (compile scope!), `spring-context`, `spring-web`, `jackson-annotations`, `jakarta.persistence-api`, `slf4j-api`, `lombok` (provided) |
| `common-security` | `common-core` | `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-validation`, `spring-boot-autoconfigure` |
| `common-logging` | `common-core` | `spring-boot-starter-aop`, `spring-security-core` (optional), `spring-security-oauth2-jose` (optional), `spring-web` (optional), `spring-webmvc` (optional), `jakarta.servlet-api` (provided) |
| `common-keycloak` | `common-core` | `spring-web`, `httpclient5`, `opentelemetry-api`, `spring-security-oauth2-core`, `jackson-annotations` |
| `common-kafka` | **none** | `spring-kafka`, `spring-retry`, `jackson-databind`, `jackson-datatype-jsr310`, `opentelemetry-api` (provided), `spring-boot-starter-actuator` (optional) |
| `common-storage` | **none** | `software.amazon.awssdk:s3`, `software.amazon.awssdk:apache-client`, `spring-boot-starter` |
| `common-spring` | `common-core`, `common-security`, `common-logging`, `common-keycloak`, `common-kafka` *(optional)*, `common-storage` *(optional)* | `spring-boot-starter-web`, `-validation`, `-oauth2-resource-server`, `-actuator`, `spring-boot-starter-opentelemetry`, `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `spring-boot-restclient`, `springdoc-openapi-starter-webmvc-ui`, `mapstruct`, `resilience4j-spring-boot4`, `modelmapper` (optional), `opencsv` (optional), `spring-dotenv`, `httpclient5`, `spring-tx` (optional) |

**Three structural facts that the READMEs would not tell you:**

1. **`common-core` is not "pure Java".** Its own `package-info.java` (lines 1–10) claims *"Pure-Java contracts shared by every microservice… Anything that requires the Spring runtime should live in `common-spring` instead."* The POM contradicts this: `common-core/pom.xml` lines 45–48 declare **`spring-boot-starter-data-jpa` in compile scope**. A service depending only on `common-core` drags in the whole JPA/Hibernate stack. `AbstractMappedEntity.java:3-10` imports `jakarta.persistence.*` **and** `org.springframework.data.jpa.domain.support.AuditingEntityListener`, and `BusinessException.java:4` imports `org.springframework.http.HttpStatus`. The package-info is aspirational, not descriptive.
2. **`common-kafka` and `common-storage` depend on nothing internal.** They are leaves that duplicate `common-core`'s role (each defines its own exception type instead of reusing `BusinessException`/`ErrorCode`).
3. **`common-spring` is a fat aggregator, not a thin starter.** Its `optional` markers on `common-kafka`/`common-storage` mean those modules are **not** transitively pulled; the `AutoConfiguration.imports` of `common-spring` deliberately omits the per-module auto-configs (the file's own comment explains that re-exporting them broke `AutoConfigurationSorter` for consumers that did not pull the optional modules).

### 2.2 Per-module responsibilities and public API surface

#### `common-core` — domain vocabulary, not a framework layer
**Responsibility:** the platform's shared vocabulary — error catalogue, API path constants, MDC keys, JPA base entities, wire envelopes, small pure helpers, i18n facade.
**Public API:** `BusinessException` (+10 static factories, `of`/`ofKey`/`badRequest`/`unauthorized`/`forbidden`/`notFound`/`conflict`/`internalServerError`), `ErrorCatalog` (interface: `getCode`/`getMessageKey`/`getHttpStatus`), `ErrorCode` (enum, ~110 constants), `ApiResponse<T>` (record + `ok`/`message`/`error` factories), `PageResponse<T>`, `AbstractMappedEntity` (`@MappedSuperclass`), `SoftDeletable` (`@MappedSuperclass`), `AuditActor` (`require`/`system`), `Messages` (static facade), `MoneyUtils`, `DateTimeUtils`, `ApiPaths`, `MdcKey`, `OutboxStatus`, `PageableConstant`.
**Hidden coupling:** `common-core` already depends on Spring Web (`HttpStatus`), Spring Data JPA (`@CreatedDate`), and Jackson annotations — so "framework-free core" is not achievable in this codebase as built.

#### `common-kafka` — Kafka producer/consumer building blocks
**Responsibility:** the shared Kafka wire contract (topics, producer template, listener container factory, consumer base class, DLT replay tooling).
**Public API:** `KafkaProperties` (+ `buildProducerProperties()`/`buildConsumerProperties()`), `KafkaAutoConfiguration`, `KafkaMessagePublisher` (sync `publish`, `publishAsync`, `publishBatch` + `OutboxMessage`/`BatchOutcome`/`RecordOutcome` records), `BaseKafkaListenerConfig<K>` (abstract), `BaseKafkaConsumer<K,V>` (abstract), `PoisonRecordException`, `KafkaPublishException`, `KafkaTopicContract` (+ `Topic` record, `Mode` enum, `find`/`require`), `OutboxBatchOrdering`, `JsonKafkaSerializer` *(deprecated-for-removal)*, `JsonKafkaDeserializer`, `DeadLetterReplayService`, `DeadLetterReplayEndpoint`, `KafkaProperties.Retry`/`ProducerConfig`/`ConsumerConfig`.
**Wiring:** `META-INF/spring/...AutoConfiguration.imports` → `com.shop.common.kafka.config.KafkaAutoConfiguration`.

#### `common-keycloak` — Keycloak admin/token adapter
**Responsibility:** talk to Keycloak's admin REST API and token endpoint, with trace propagation and a credential posture guard.
**Public API:** `KeycloakAdminClient` (`createUser`, `deleteUser`, `enableUser`/`disableUser`, `logoutUser`, `resetUserPassword`, `assignRealmRole(s)`, `removeRealmRole`, `isDirectAccessGrantEnabled`, `hasAdminCredentials`, `hasServiceAccountCredentials`), `KeycloakTokenClient` (`login`, `refreshToken`, `exchangeAuthorizationCode`, `logout`, `verifyCredentials`), `KeycloakProperties` (endpoint derivations), `KeycloakTokenResponse`, `KeycloakClientException`, `KeycloakAutoConfiguration`.
**Wiring:** `AutoConfiguration.imports` → `com.shop.common.keycloak.config.KeycloakAutoConfiguration`.

#### `common-logging` — AOP performance logging + structured audit trail
**Responsibility:** two independent concerns packaged together — (a) `@LogPerformance`/`@Loggable` timing/tracing via AOP, (b) `@Audited` structured JSON audit lines with a bounded, non-blocking writer.
**Public API:** `@LogPerformance`, `@Loggable`, `LogField`, `LoggerAspect`, `@Audited`, `AuditEvent` (record + `toJson`), `AuditEventWriter` (interface: `write`/`discardedEvents`/`close`), `BoundedAsyncAuditEventWriter` (`create()`/`create(path)`), `AuditActorResolver` (+ `Actor` record), `AuditResourceResolver`, `AuditAspect`, `AuditLogProperties`, `PerformanceLogProperties`, `AuditAutoConfiguration`, `LoggingAutoConfiguration`.
**Wiring:** `AutoConfiguration.imports` → `AuditAutoConfiguration`, `LoggingAutoConfiguration`.

#### `common-security` — resource-server security stack
**Responsibility:** the default `SecurityFilterChain`, JWT decoder with lazy JWKS acquisition, validator chain (issuer/timestamp/aud/azp), role→authority mapping, CORS, and a service-caller guard.
**Public API:** `BaseSecurityConfig` (`securityFilterChain`, `jwtDecoder`, `jwtAuthenticationConverter`), `SecurityProperties` (+ `EndpointRule`, `Cors`, `PlatformDefaults`), `LazyJwtDecoder`, `JwtRolesConverter`, `JwtClaimExtractor`, `AuthenticatedUser`, `ServiceCallerGuard`, `CorsAutoConfigurer`, `SecurityAutoConfiguration`.
**Wiring:** `AutoConfiguration.imports` → `com.shop.common.security.config.SecurityAutoConfiguration` (which `@Import`s `BaseSecurityConfig` + `CorsAutoConfigurer` and `@EnableMethodSecurity`).

#### `common-spring` — the "batteries-included" starter
**Responsibility:** compose the other modules and add web-tier, i18n, tracing, JPA-auditing and mapping concerns; ship the fleet's default `application.yml`.
**Public API:** `CommonLibraryStarter` (reference `@SpringBootApplication`), `CommonProperties`, `WebAutoConfiguration`/`I18nAutoConfiguration`/`TracingAutoConfiguration`/`ModelMapperAutoConfiguration`/`JpaAuditingAutoConfiguration`/`Jackson2ObjectMapperAutoConfiguration`, `ApiExceptionHandler`, `CorrelationIdFilter`, `HttpLoggingFilter`, `HttpLogProperties`, `I18nProperties`, `RequestUtils`, `TraceparentInterceptor`, `MdcOtelCurrentTraceContext`, `RecordValueReader`, `BaseMapper<M,V>`/`EntityCreateUpdateMapper<M,V,R>` (MapStruct), plus `src/main/resources/application.yml` + `messages/messages_{en,vi}.properties`.
**Wiring:** `AutoConfiguration.imports` → six auto-configs listed above.

#### `common-storage` — S3-compatible object storage
**Responsibility:** a backend-agnostic `ObjectStorageService` façade over the AWS SDK v2, plus its auto-configuration.
**Public API:** `ObjectStorageService` (interface, 13 methods incl. a `default openStream`), `S3ObjectStorageService`, `StorageObject` (record), `StorageProperties`, `S3ClientFactory`, `StorageException`, `ObjectStorageAutoConfiguration`.
**Wiring:** `AutoConfiguration.imports` → `com.shop.common.storage.config.ObjectStorageAutoConfiguration`.

---

## 3. Dependency-inversion and SOLID assessment

### 3.1 Does the platform layer leak framework types into abstraction layers?

**Yes, in four measurable places.**

| # | Leak | Evidence | Verdict |
| --- | --- | --- | --- |
| L1 | **JPA/Spring Data into the "core" domain module** | `utils/common-core/pom.xml:45-48` — `spring-boot-starter-data-jpa` at compile scope; `common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java:3-10` imports `jakarta.persistence.Column/MappedSuperclass/EntityListeners` **and** `org.springframework.data.annotation.CreatedDate`, `org.springframework.data.jpa.domain.support.AuditingEntityListener`; `SoftDeletable.java:3-4` imports `jakarta.persistence.*`; `common-core/src/main/java/com/shop/common/core/package-info.java:1-10` asserts the opposite ("Pure-Java contracts… Anything that requires the Spring runtime should live in `common-spring`"). | **Violation.** The abstraction layer *is* the framework layer. The `package-info` is false documentation. |
| L2 | **Spring Web `HttpStatus` inside the error catalogue** | `common-core/.../exception/ErrorCatalog.java:3,15` (`HttpStatus getHttpStatus()`); `ErrorCode.java:142` (`private final HttpStatus httpStatus`); `BusinessException.java:4,18` (`private final HttpStatus status`); `KeycloakClientException.java:3,11`; `StorageException` has no status. | **Violation (deliberate, documented).** The catalogue cannot be consumed by a non-web service (e.g. a Kafka-only worker) without pulling `spring-web`. Fix: `int getHttpStatusValue()` on the port, with the `HttpStatus` mapping in the adapter. |
| L3 | **Kafka types inside the shared "contract"** | `common-kafka/.../contract/KafkaTopicContract.java` is framework-free (good), but `common-kafka/.../consumer/BaseKafkaConsumer.java:8-9` imports `org.springframework.kafka.support.KafkaHeaders` and `org.springframework.messaging.MessageHeaders`; `BaseKafkaListenerConfig.java:4-12` imports `org.apache.kafka.common.serialization.*` and `org.springframework.kafka.*`; `KafkaMessagePublisher.java:8-13` imports `org.apache.kafka.clients.producer.ProducerRecord` and `org.springframework.kafka.core.KafkaTemplate`. | **Violation.** There is no Kafka-free port: `common-kafka` offers `KafkaTemplate`-typed APIs directly, so any service using it is welded to Spring Kafka. |
| L4 | **Spring Security types in the audit actor resolver** | `common-logging/.../audit/AuditActorResolver.java:3-6` imports `Authentication`, `SecurityContextHolder`, `Jwt`. | **Acceptable, with a mitigation.** The class is guarded by `@ConditionalOnClass` (`AuditAutoConfiguration.java:27-28`) and the class body only touches the types inside method bodies. The javadoc at lines 26-28 states this explicitly. It is a *deliberate* optional coupling, correctly declared (`common-logging/pom.xml:29-48`, all `optional`). |

### 3.2 Are the ports defined in the right module?

| Port / abstraction | Defined in | Correct module? | Assessment |
| --- | --- | --- | --- |
| `ErrorCatalog` | `common-core` | **Yes** | A pure interface with `getCode`/`getMessageKey`/`getHttpStatus`. `BusinessExceptionTest.java:10-37` proves a *service-local* enum can implement it — this is a genuine, working Open/Closed extension point for decentralized error catalogues. |
| `AuditEventWriter` | `common-logging` | **Yes** | The only real port in the codebase. `AuditAutoConfiguration.java:34-38` is `@ConditionalOnMissingBean`, and `AuditAutoConfigurationTest.java:43-50` proves a user-supplied writer wins. |
| `ObjectStorageService` | `common-storage` | **Yes** | Backend-agnostic interface (`ObjectStorageService.java:14-65`); `S3ObjectStorageService` is the adapter. `ObjectStorageAutoConfiguration.java:63-66` is `@ConditionalOnMissingBean`, so a service can substitute MinIO/OSS. |
| `AuditActorResolver` / `AuditResourceResolver` | `common-logging` | **No — they are not ports** | They are `final` classes with `private` constructors and only `static` methods (`AuditActorResolver.java:30,41-42`; `AuditResourceResolver.java:19,23-24`). A service that needs a different actor model (e.g. "the delegated actor" or "the impersonating admin") **cannot** substitute it. The `AuditAspect` takes only an `AuditEventWriter` (`AuditAspect.java:36-40`), not an actor resolver. |
| **Kafka publish port** | — | **Missing** | `KafkaMessagePublisher` is a *concrete class* (`KafkaMessagePublisher.java:73`) bound to `KafkaTemplate<String,String>` (`:89`). It is not an interface, so a service cannot substitute a non-Kafka publisher, and a unit test must mock a Spring Kafka type (exactly what `KafkaMessagePublisherTest.java:42` does). |
| **Kafka consume port** | — | **Missing** | `BaseKafkaConsumer` is an abstract *class* coupled to `MessageHeaders` (`BaseKafkaConsumer.java:52,60,72`). It is a Template Method base, not a port. |

### 3.3 SOLID, module by module

- **SRP** — mostly clean, with one exception: `common-logging` bundles two unrelated concerns (AOP performance logging *and* the audit pipeline) that only share the `common-core` dependency. `LoggerAspect` and `AuditAspect` share no code and could be split. `common-spring` is a second SRP violation: it mixes a *starter* (auto-configuration) with *shared application code* (`ApiExceptionHandler`, `CorrelationIdFilter`, `HttpLoggingFilter`, `RequestUtils`, `RecordValueReader`) and a *reference application* (`CommonLibraryStarter`).
- **OCP** — good, evidenced: `ErrorCatalog` (per-service error enums, `BusinessExceptionTest.java:10-37`), `AuditEventWriter` (`AuditAutoConfigurationTest.java:43-50`), `ObjectStorageService` (`ObjectStorageAutoConfiguration.java:64-66`), `BaseKafkaListenerConfig`/`BaseKafkaConsumer` (abstract bases extended by every service).
- **LSP** — acceptable. `BaseKafkaConsumer`'s three `processMessage` overloads are differentiated by arity, not by type; the `K` parameter of `BaseKafkaListenerConfig` is explicitly documented as unused for deserialization (`BaseKafkaListenerConfig.java:31-35`), which is an honest LSP smell: the generic is a lie kept for source compatibility.
- **ISP** — acceptable. `AuditEventWriter` has three narrow methods; `ObjectStorageService` has 13 but they are all storage operations.
- **DIP** — **partially violated.** The domain/abstraction modules (`common-core`, `common-security`, `common-logging`, `common-storage`) depend on Spring; the shared libraries depend on *concrete* third-party infrastructure classes (`NimbusJwtDecoder` at `BaseSecurityConfig.java:116`, `KafkaTemplate` at `KafkaMessagePublisher.java:89`, `S3Client` at `S3ObjectStorageService.java:35`) rather than on ports. The pattern used across the fleet is *auto-configuration + `@ConditionalOnMissingBean` back-off*, which is the Spring-idiomatic substitute for DIP — and it works (`AuditAutoConfigurationTest`, `StoragePropertiesCredentialsTest`). Where the pattern is missing (Kafka publish/consume, audit actor), the result is the leak inventory in 3.1.

### 3.4 Dependency-cycle evidence (self-documented)

`common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java:29-41` documents the cycle explicitly:

> *"common-keycloak cannot depend on common-spring (cycle: common-spring depends on common-keycloak), so the injection mirrors `TraceparentInterceptor` inline instead of reusing the shared class."*

The consequence is **duplicated tracing logic**: `common-keycloak` re-implements W3C injection inline (`KeycloakAutoConfiguration.java:42-52`), and `common-kafka` re-implements it a *third* time (`KafkaMessagePublisher.java:79-87`, `239-246`). Three copies of the same propagation seam, none of them shared, because the port (`common-spring.tracing.TraceparentInterceptor`) lives in the module that depends on them. The port is in the wrong module.

---

## 4. SINGLETON & THREAD-SAFETY AUDIT

Every Spring-managed bean in scope was inspected for: mutable instance fields, static mutable fields, shared collections/buffers, cached request state, shared builders, non-thread-safe collaborators, lazy initialisation, `@Scheduled`, `@Async`, counters, and check-then-act races.

### 4.1 Inventory of Spring-managed beans in scope

| Bean (class) | Module | Registration | Mutable instance state | Static mutable state | Verdict |
| --- | --- | --- | --- | --- | --- |
| `KafkaAutoConfiguration` | common-kafka | `@AutoConfiguration` | none (all methods build locals) | none | **Safe** |
| `KafkaProperties` | common-kafka | `@ConfigurationProperties` | **all fields mutable via setters** (`KafkaProperties.java:43-77`) | none | Mutable config bean — safe in practice (bound once at startup), see SL-30 |
| `KafkaMessagePublisher` | common-kafka | `KafkaAutoConfiguration.java:97-102` | `kafkaTemplate` (final), `publishTimeoutMs` (final) | `W3C` propagator (thread-safe, immutable), `HEADER_SETTER` (stateless lambda) | **Safe** — verified below |
| `BaseKafkaListenerConfig` (subclasses) | common-kafka | subclass `@Bean` | `keyType`, `kafkaProperties`, `kafkaTemplate` — all final | none | **Safe** |
| `BaseKafkaConsumer` (subclasses) | common-kafka | subclass bean | `log` (final), inherited `WIRE_MAPPER` | **`WIRE_MAPPER` static shared `ObjectMapper`** (`BaseKafkaConsumer.java:39-40`) | **Safe for concurrency, defective for schema evolution** — see SL-06 |
| `DeadLetterReplayService` | common-kafka | `KafkaAutoConfiguration.java:80-86` | `template`, `bootstrapServers`, `groupIdPrefix` (final) | none | **Safe but see SL-04/SL-05** |
| `DeadLetterReplayEndpoint` | common-kafka | `KafkaAutoConfiguration.java:91-94` | `replayService` (final) | none | **Safe** |
| `KeycloakAutoConfiguration` | common-keycloak | `@AutoConfiguration` | none | `TRACE_HEADERS_SETTER` (stateless method ref) | **Safe** |
| `KeycloakProperties` | common-keycloak | `@ConfigurationProperties` | **all fields mutable** (`@Getter @Setter`, `KeycloakProperties.java:7-8`) | none | Mutable config bean — see SL-30 |
| `KeycloakAdminClient` | common-keycloak | `KeycloakAutoConfiguration.java:87-99` | `restClient` + endpoint/credential fields (final), **`cachedToken` = `AtomicReference<CachedAdminToken>`** (`:398`) | none | **Safe but see SL-01/SL-02** |
| `KeycloakTokenClient` | common-keycloak | `KeycloakAutoConfiguration.java:60-67` | `restClient`, endpoints, `clientId`, `clientSecret` (final) | none | **Safe** |
| `AuditAspect` | common-logging | `AuditAutoConfiguration.java:41-44` | `writer` (final) | none | **Safe** |
| `LoggerAspect` | common-logging | `LoggingAutoConfiguration.java:27-30` | `props` (final) | `perfLog`, `log` (loggers, thread-safe) | **Safe** |
| `BoundedAsyncAuditEventWriter` | common-logging | `AuditAutoConfiguration.java:35-38` | `executor`, `sink`, `discarded`/`failed`/`lastSinkWarnNanos` (`AtomicLong`) | `auditStdout` logger | **Safe** — verified below; `close()` is invoked by Spring's inferred destroy method |
| `AuditActorResolver` | common-logging | static utility | n/a | `ANONYMOUS` (immutable record) | **Safe** |
| `AuditEvent` | common-logging | value object | immutable record | — | **Safe** |
| `BaseSecurityConfig` | common-security | `@Import` from `SecurityAutoConfiguration.java:32` | `properties` (final) | none | **Safe** |
| `LazyJwtDecoder` | common-security | `BaseSecurityConfig.java:113-120` | `factory` (final), **`delegate` = `AtomicReference<JwtDecoder>`** (`:41`) | none | **Concurrency-safe but the javadoc's guarantee is false** — see SL-03 |
| `CorsAutoConfigurer` | common-security | `@Import` | none | none | **Safe** |
| `ServiceCallerGuard` | common-security | `SecurityAutoConfiguration.java:36-39` | none | none | **Safe** |
| `JwtRolesConverter` | common-security | `@Bean` in `BaseSecurityConfig.java:153-156` | none | none | **Safe** |
| `SecurityProperties` | common-security | `@ConfigurationProperties` | immutable record | none | **Safe** |
| `WebAutoConfiguration` | common-spring | `@AutoConfiguration` | none | none | **Safe** |
| `CorrelationIdFilter` | common-spring | `WebAutoConfiguration.java:32-34` | none | none | **Safe** (but SL-14) |
| `HttpLoggingFilter` | common-spring | `WebAutoConfiguration.java:39-42` | `props` (final) | `PATH_MATCHER` (`AntPathMatcher`, thread-safe), `SKIP_PATHS` (immutable), `SENSITIVE_JSON_PATTERN` (`Pattern`, thread-safe) | **Safe** (but SL-15/SL-16) |
| `ApiExceptionHandler` | common-spring | `WebAutoConfiguration.java:47-49` | none | `log` | **Safe** |
| `I18nAutoConfiguration` | common-spring | `@AutoConfiguration` | `props` (final) | none | **Safe** |
| `Messages` | common-core | static facade | — | **`private static volatile MessageSource messageSource`** (`Messages.java:32`) | **Safe** (volatile publication), see SL-31 |
| `Jackson2ObjectMapperAutoConfiguration` | common-spring | `@AutoConfiguration` | none | none | **Safe** (but SL-07) |
| `JpaAuditingAutoConfiguration` | common-spring | `@AutoConfiguration` | none | none | **Safe** |
| `ModelMapperAutoConfiguration` | common-spring | `@AutoConfiguration` | none | none | **Safe — verified against the ModelMapper 3.2.6 source** (see 4.4) |
| `TracingAutoConfiguration` | common-spring | `@AutoConfiguration` | none | none | **Safe** |
| `TraceparentRestClientBuilderPostProcessor` | common-spring | `TracingAutoConfiguration.java:67-69` | **`interceptor` = `AtomicReference`** (`:82`), `beanFactory` (plain field) | none | **Safe** — see 4.4 |
| `MdcOtelCurrentTraceContext` | common-spring | `TracingAutoConfiguration.java:127-129` | none | none | **Safe** (MDC is thread-local) |
| `RecordValueReader` | common-spring | `ModelMapperAutoConfiguration.java:33` | none | none | **Safe** |
| `ObjectStorageAutoConfiguration` | common-storage | `@AutoConfiguration` | none | none | **Safe** (but SL-08) |
| `StorageProperties` | common-storage | `@ConfigurationProperties` | **all fields mutable** | none | Mutable config bean — SL-30 |
| `S3ObjectStorageService` | common-storage | `ObjectStorageAutoConfiguration.java:63-72` | `s3Client`, `presigner`, `properties` (final) | none | **Safe** |
| `S3ClientFactory` | common-storage | static utility | n/a | none | **Safe** |

**No `@Scheduled` method and no `@Async` method exists anywhere in these modules** (verified by grep over the whole `utils/` tree). Consequently there is **no scheduled-counter / check-then-act counter defect of the classic kind** (no `AtomicInteger` increment outside a lock, no `HashMap` used as a shared cache without synchronisation).

### 4.2 Thread-safety defects and suspicious constructs, with exact evidence

#### SL-01 — `KeycloakAdminClient.getAdminAccessToken()` holds a JVM monitor across a network call
`utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:402-449`
```java
private String getAdminAccessToken() {
    CachedAdminToken current = cachedToken.get();
    if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(SKEW_SECONDS))) {
        return current.token();
    }
    synchronized (this) {
        ...
        KeycloakTokenResponse response = restClient.post()...retrieve().body(...);
```
**Unsafe construct:** `synchronized (this)` on a **shared singleton bean**, with an HTTP round-trip to Keycloak inside the monitor. The double-checked locking with `AtomicReference` is sound, but every admin call in the service serialises on this one monitor while the IdP is slow or hung. With N concurrent admin operations, request threads pile up behind one lock.
**Severity:** HIGH (availability). **Root cause:** lock scope includes I/O.

#### SL-02 — `KeycloakAdminClient` silently falls back to the master-realm *password* grant
`KeycloakAdminClient.java:118-121` and `:414-422`
```java
public boolean hasServiceAccountCredentials() {
    return adminClientSecret != null && !adminClientSecret.isBlank()
            && serviceAccountTokenEndpoint != null && !serviceAccountTokenEndpoint.isBlank();
}
...
if (hasServiceAccountCredentials()) {
    return getServiceAccountAccessToken();
}
MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
form.add(GRANT_TYPE, PASSWORD);
form.add(USERNAME, adminUsername);
form.add(PASSWORD, adminPassword);
```
**Unsafe construct:** the service-account (strong) path requires **two** properties, but the fallback (weak) path requires only the master-realm password. Configure `admin-client-secret` and forget the endpoint and the client silently downgrades to a master-realm password grant — the exact outcome F-078 was written to prevent. `KeycloakAdminClientServiceAccountTest.java:139-155` proves there is **no** fallback *when both* are configured; there is **no** test for the half-configured case.
**Severity:** HIGH (security posture).

#### SL-03 — `LazyJwtDecoder` builds **N** decoders under concurrent first-request load (javadox guarantee is false)
`utils/common-security/src/main/java/com/shop/common/security/config/LazyJwtDecoder.java:48-55, 80-95`
```java
public Jwt decode(String token) throws JwtException {
    JwtDecoder live = delegate.get();
    if (live == null) {
        live = buildOrFallback();
    }
    return live.decode(token);
}
...
private JwtDecoder buildOrFallback() {
    JwtDecoder live;
    try {
        live = factory.get();              // <-- the expensive OIDC discovery fetch happens HERE
    } catch (Exception e) {
        throw new JwtException("Failed to build JWT decoder: " + e.getMessage(), e);
    }
    if (!delegate.compareAndSet(null, live)) { ... }
    return live;
}
```
**Unsafe construct:** the CAS at `:87` only prevents the second decoder from being *installed*; the expensive `factory.get()` at `:83` has **already run**. The class javadoc claims (lines 32-33): *"a CAS on the delegate ensures only one real decoder is built even under concurrent first-request load."* **That claim is false** — N concurrent first requests perform N OIDC-discovery fetches against Keycloak. Worse, if the *losing* thread's fetch throws, it raises `JwtException` (line 85) → 401, even though the winner successfully built a decoder.
**Severity:** MEDIUM (correctness of the stated invariant + IdP thundering herd). The `BaseSecurityConfigJwtDecoderLazyTest.java:12-36` only proves the *bean factory* is non-blocking — it does not cover the concurrent first-request path.

#### SL-04 — `LazyJwtDecoder` performs a synchronous OIDC discovery on the **request** thread with no negative cache
`LazyJwtDecoder.java:62-78` (`prewarm()` starts a daemon thread) and `:80-95`.
**Unsafe construct:** if the pre-warm fails (Keycloak down at boot), **every** request thread that needs a JWT performs a synchronous `factory.get()` → `NimbusJwtDecoder.withIssuerLocation(...)` → blocking HTTP discovery. There is no negative cache, no circuit breaker, and no timeout configured on the discovery client. A Keycloak outage turns into request-thread exhaustion in every service that depends on `common-security`.
**Severity:** MEDIUM–HIGH (availability, cascading failure).

#### SL-05 — `DeadLetterReplayService.replay()` re-reads the whole DLT on every invocation and re-publishes already-replayed records
`utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayService.java:71-77, 87-124`
```java
props.put(ConsumerConfig.GROUP_ID_CONFIG, groupIdPrefix + "-dlq-replay-" + System.nanoTime());
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
...
while (replayed + skipped + failed < maxRecords) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
```
**Unsafe construct:** a **new consumer group per invocation** combined with `auto.offset.reset=earliest` means every replay pass restarts from the beginning of the DLT. The `commitSync()` calls at `:101`, `:118`, `:122` are therefore **meaningless** — the offsets are discarded when the group id changes. A second operator-triggered replay re-publishes the records the first pass already replayed, i.e. the tool **duplicates business events** on every run after the first. The `DeadLetterReplayServiceTest.java:85-112` asserts only a single pass and therefore cannot see this.
**Severity:** HIGH (duplicate business events).

#### SL-06 — `DeadLetterReplayService.replay()` commits the offset of a record it **failed** to republish
`DeadLetterReplayService.java:104-123`
```java
try {
    template.send(new ProducerRecord<>(originalTopic, record.key(), record.value())).get();
    replayed++;
    ...
} catch (Exception ex) {
    failed++;
    consecutiveFailures++;
    ...
    if (consecutiveFailures >= maxConsecutiveFailures) { consumer.commitSync(); return ...; }
}
consumer.commitSync();     // <-- also reached after a FAILED publish
```
**Unsafe construct:** the `commitSync()` at line 122 is outside the `try`, so it executes for failed records too. Within a pass the failed record is never retried, and the `maxConsecutiveFailures` guard only bounds the loop. Combined with SL-05 (new group per pass) the practical effect is masked, but the code states an intent it does not implement.
**Severity:** MEDIUM.

#### SL-07 — `DeadLetterReplayService` blocks forever on a hung broker and loses trace context
`DeadLetterReplayService.java:105` — `template.send(...).get();` with **no timeout**. A broker that accepts the connection but never acks blocks the actuator thread indefinitely.
`DeadLetterReplayService.java:105` — `new ProducerRecord<>(originalTopic, record.key(), record.value())` builds the record **without** the W3C `traceparent` header, unlike `KafkaMessagePublisher.traceparentRecord()` (`KafkaMessagePublisher.java:239-246`). Replayed events therefore arrive with **no trace linkage**, breaking the trace for every downstream consumer of a replayed event.
**Severity:** MEDIUM (availability + observability).

#### SL-08 — `BaseKafkaConsumer.WIRE_MAPPER` is a bare `ObjectMapper` that rejects unknown properties
`utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:39-40, 97-102`
```java
private static final ObjectMapper WIRE_MAPPER =
        new ObjectMapper().registerModule(new JavaTimeModule());
...
V bound = WIRE_MAPPER.treeToValue(node, type);
```
**Unsafe construct / defect:** `new ObjectMapper()` keeps Jackson's default `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = true`. Spring Boot **disables** that feature on its own mapper (verified in the dependency source: `spring-boot-jackson-4.1.1-sources.jar` → `org/springframework/boot/jackson/autoconfigure/JacksonAutoConfiguration.java:518` → `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`). The same divergence exists in `Jackson2ObjectMapperAutoConfiguration.java:27-29`.
**Consequence:** the HTTP boundary tolerates a new DTO field; the Kafka boundary does **not**. A producer that adds one field to a lifecycle event turns **every** record into a `PoisonRecordException` → retry × 4 → DLT, on every consumer, fleet-wide, with no deploy-time signal.
The mapper is *thread-safe* for concurrent reads (a configured `ObjectMapper` is safe to share), so this is a **contract** defect, not a race. Note also the class javadoc's claim (lines 30-32) that trusted-package configuration is unnecessary is **correct** — no `enable.default.typing` is enabled anywhere, so no polymorphic gadget surface exists.
**Severity:** HIGH (fleet-wide availability on schema evolution).

#### SL-09 — `BaseKafkaConsumer` javadoc contradicts its code about DLT routing
`BaseKafkaConsumer.java:47-51` states: *"A decode failure logs at ERROR and **ack-skips** without invoking the handler (fleet containment: **no DLT**, no container crash)."*
`BaseKafkaConsumer.java:111-116` actually **throws**:
```java
} catch (Exception ex) {
    log.error("Failed to decode Kafka payload for {} — routing to the dead-letter topic: {}", ...);
    throw new PoisonRecordException("Undecodable Kafka payload for " + type.getSimpleName(), ex);
}
```
The **code is right** and the javadoc is stale — `BaseKafkaConsumerTypedBindTest.java:70-78` and `BaseKafkaConsumerDeadLetterTest.java:84-94` both assert the throw-and-DLT behaviour. The defect is the misleading contract comment, which will lead a reviewer to believe there is no DLT path.
**Severity:** MEDIUM (documentation vs. behaviour; misleads incident response).

#### SL-10 — `JsonKafkaDeserializer` throws `RuntimeException`, which defeats the `ErrorHandlingDeserializer` DLT contract
`utils/common-kafka/src/main/java/com/shop/common/kafka/serialization/JsonKafkaDeserializer.java:33-43`
```java
public T deserialize(String topic, byte[] data) {
    ...
    } catch (Exception ex) {
        throw new RuntimeException("Failed to deserialize Kafka payload for topic " + topic, ex);
    }
}
```
Its own javadoc (lines 12-13) says: *"Throws a `RuntimeException` when the payload cannot be parsed so that the `ErrorHandlingDeserializer` wrapper can route it to the DLT."* Spring's `ErrorHandlingDeserializer` only converts a delegate failure into a DLT-routable `RecordDeserializationException` when the delegate throws **`SerializationException`**; a bare `RuntimeException` propagates as a *handler* failure and is retried and then **recovered (skipped)**, i.e. the record is silently dropped, not dead-lettered. The class is not on the live path today (`BaseKafkaListenerConfig.java:109-111` uses `StringDeserializer`), but it is public API and the javadoc is wrong.
**Severity:** MEDIUM (latent data loss if a service adopts it).

#### SL-11 — `JsonKafkaSerializer` silently degrades a failed serialization to a **non-JSON** payload
`utils/common-kafka/src/main/java/com/shop/common/kafka/serialization/JsonKafkaSerializer.java:48-58`
```java
public byte[] serialize(String topic, T data) {
    ...
    try {
        return objectMapper.writeValueAsBytes(data);
    } catch (Exception ex) {
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }
}
```
**Unsafe construct:** a Jackson failure (e.g. a self-referencing DTO, an unmapped type) produces `data.toString()` on the wire — a payload that is **not JSON**. The consumer's `WIRE_MAPPER.readTree` then fails → poison record → DLT, and the original data is lost with no signal that the failure was a serializer failure. This serializer is still wired as the default `kafkaTemplate` value serializer (`KafkaAutoConfiguration.java:53-56`), so the path is live.
**Severity:** MEDIUM (silent wire-format corruption).

#### SL-12 — `KafkaProperties.buildConsumerProperties()` leaves critical consumer settings to framework defaults
`utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:98-106`
```java
public Map<String, Object> buildConsumerProperties() {
    Map<String, Object> props = new HashMap<>();
    props.put("bootstrap.servers", bootstrapServers);
    props.put("group.id", consumer.getGroupId());
    props.put("auto.offset.reset", consumer.getAutoOffsetReset());
    props.put("key.deserializer", ...StringDeserializer.class.getName());
    return props;
}
```
**Unsafe construct:** `enable.auto.commit`, `isolation.level`, `max.poll.records`, `max.poll.interval.ms` and `session.timeout.ms` are **not pinned**. Relying on implicit defaults for commit semantics on a financial event bus is an operational hazard, and `isolation.level` is required for `read_committed` semantics if transactions are ever introduced.
**Severity:** MEDIUM.

#### SL-13 — `KafkaProperties.ConsumerConfig.groupId` default is a **fleet-wide** shared identity
`KafkaProperties.java:226-228`
```java
public static class ConsumerConfig {
    private String groupId = "shop-service";
```
Any `@KafkaListener` that forgets to pin `groupId` inherits `shop-service`. Two services configured with that id would **compete for the partitions** of a shared topic and each would silently receive only a subset of the events. The base class documents the hazard at length (`BaseKafkaListenerConfig.java:48-59`) and a contract test enforces the pinning (`KafkaListenerGroupIdContractTest.java:36-62`), but the **default itself is a trap**: the shared `application.yml` does not set `shop.kafka.consumer.group-id`, so the trap is armed for every service that does not pin.
**Severity:** MEDIUM (silent event loss on a shared topic).

#### SL-14 — `CorrelationIdFilter` trusts an unvalidated client-supplied header and writes it into the MDC
`utils/common-spring/src/main/java/com/shop/common/spring/web/filter/CorrelationIdFilter.java:39-50, 61-64`
```java
String correlationId = headerOrNew(request, CORRELATION_ID_HEADER);
...
MDC.put(MdcKey.CORRELATION_ID, correlationId);
...
response.setHeader(CORRELATION_ID_HEADER, correlationId);
...
private static String headerOrNew(HttpServletRequest request, String header) {
    String value = request.getHeader(header);
    return StringUtils.hasText(value) ? value : UUID.randomUUID().toString();
}
```
**Unsafe construct:** the header value is accepted **verbatim** — no length cap, no character whitelist, no UUID/ULID check — and is then (a) placed in the MDC, which the logging pattern renders into **every** log line (`application.yml:106` → `"[%X{traceId:-},%X{spanId:-}] "`), and (b) echoed back in a response header. A client can inject CR/LF, ANSI escapes, or megabyte-long strings into the fleet's logs (log forging / log bloat), and can smuggle a chosen "traceId" into the trace pipeline (it is copied into `MdcKey.TRACE_ID` at line 45-47).
**Severity:** HIGH (log injection / trace spoofing).

#### SL-15 — `RequestUtils.getClientIp()` trusts client-supplied proxy headers unconditionally
`utils/common-spring/src/main/java/com/shop/common/spring/web/util/RequestUtils.java:12-18, 26-35`
```java
private static final String[] IP_HEADER_CANDIDATES = {
        "X-Original-Forwarded-For", "X-Forwarded-For", "Proxy-Client-IP",
        "WL-Proxy-Client-IP", "X-Real-IP"
};
...
for (String header : IP_HEADER_CANDIDATES) {
    String ip = request.getHeader(header);
    if (ip != null && !ip.isBlank() && !UNKNOWN.equalsIgnoreCase(ip)) {
        String resolved = ip.contains(",") ? ip.split(",")[0].trim() : ip;
        return normalize(resolved);
    }
}
```
**Unsafe construct:** the first non-empty proxy header wins, with **no trusted-proxy allow-list**. Any direct client can send `X-Forwarded-For: 127.0.0.1` and be treated as a local address. The shared defaults compound this: `application.yml:47` sets `server.forward-headers-strategy: framework`, so Spring's own `ForwardedHeaderFilter` also trusts those headers.
`RequestUtils.isLocalAddress(String)` (lines 43-45) exists to classify such an IP as local — it is currently **unused anywhere in the repository** (verified by grep), so the bypass is latent rather than live, but the helper is public API on the shared library.
**Severity:** HIGH (as a public API of a shared library; the `isLocalAddress` helper is a ready-made bypass primitive).

#### SL-16 — `HttpLoggingFilter` buffers the **entire** response body in heap per request
`utils/common-spring/src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java:64-74`
```java
ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(
        request, props.getRequest().getMaxBodyBytes());
ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
```
**Unsafe construct:** the request wrapper is bounded (`maxBodyBytes`, default 4096), but `ContentCachingResponseWrapper` has **no size limit** — it accumulates the complete response body in a `ByteArrayOutputStream` for every request, and the filter is registered by default (`WebAutoConfiguration.java:36-42`, `matchIfMissing = true`). A service that serves media/large reports (the fleet has `media-service`) buffers the entire payload per concurrent request — an OOM amplifier under load, even though body logging is off by default.
**Severity:** HIGH (unbounded per-request memory).

#### SL-17 — `ApiExceptionHandler` echoes the raw exception message for two request-shape errors
`utils/common-spring/src/main/java/com/shop/common/spring/web/exception/ApiExceptionHandler.java:104-115, 190-202`
```java
@ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception exception, WebRequest request) {
    return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(),
            exception.getMessage(), ...);
```
`MethodArgumentTypeMismatchException.getMessage()` embeds the **rejected value** ("Failed to convert value of type … ; For input string: \"<attacker input>\""), and `MultipartException.getMessage()` embeds the multipart part name and content type. Both are reflected into the JSON response body.
Contrast with the deliberate hardening elsewhere in the same class: `handleUnreadableMessage` (lines 117-131) explicitly refuses to surface `exception.getMessage()` "because it can contain the raw [body]", and `handleDataIntegrityViolation` (lines 204-218) uses a canned message — pinned by `ApiExceptionHandlerConstraintLeakTest.java:20-48`.
**Severity:** MEDIUM (reflected-input disclosure).

#### SL-18 — `KeycloakTokenClient.verifyCredentials()` cannot distinguish "wrong password" from "Keycloak is down"
`utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:146-160`
```java
public boolean verifyCredentials(String username, String password) {
    try {
        KeycloakTokenResponse response = login(username, password);
        ...
        return true;
    } catch (KeycloakClientException e) {
        return false;
    }
}
```
**Unsafe construct:** `KeycloakClientException` carries the upstream HTTP status (`KeycloakClientException.java:11,23-25`) — 401, 500, 503, 404 — but every one of them is collapsed into `false`. An IdP outage is reported to the caller as "invalid credentials", which typically drives account-lockout and support escalation instead of an incident.
**Severity:** MEDIUM.

#### SL-19 — `KeycloakAdminClient.updateUserEnabled()` can leave live sessions after a successful disable
`utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:262-280`
```java
restClient.put().uri(usersEndpoint + "/" + userId)....retrieve().toBodilessEntity();
if (!enabled) {
    restClient.post().uri(usersEndpoint + "/" + userId + "/logout")...retrieve().toBodilessEntity();
}
} catch (RestClientResponseException e) {
    ... throw new KeycloakClientException("Update user state failed: " + ..., ...);
}
```
**Unsafe construct:** the PUT (disable) and the POST (logout) share one `try`. If the logout fails, the caller sees "Update user state failed" — but the user **is** disabled and **still has live sessions and refresh tokens**. The same session-revocation concern the class documents at length (lines 282-294, `logoutUser`) is defeated in the disable path.
**Severity:** MEDIUM (security: a disabled account keeps its session).

#### SL-20 — `KeycloakAdminClient.buildUserPayload()` hard-codes `emailVerified=true`
`utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:570-593`
```java
payload.put("username", username);
payload.put("email", email);
payload.put("enabled", true);
payload.put("emailVerified", true);
```
**Unsafe construct:** every user created through the shared client is created with a **pre-verified** email address, bypassing the e-mail-verification step the identity provider provides. The same method also hard-codes `credential.put("temporary", false)` (line 581) while `resetUserPassword` exposes a `temporary` flag — so the two paths disagree about forced password rotation.
**Severity:** MEDIUM (security policy).

#### SL-21 — `DateTimeUtils` cache eviction is a check-then-act race with a whole-cache flush
`utils/common-core/src/main/java/com/shop/common/core/util/DateTimeUtils.java:26-35`
```java
if (!FORMATTERS.containsKey(resolvedPattern) && FORMATTERS.size() >= MAX_CACHE_SIZE) {
    FORMATTERS.clear();
}
return FORMATTERS.computeIfAbsent(resolvedPattern, DateTimeFormatter::ofPattern).format(dateTime);
```
**Unsafe construct:** `containsKey` / `size` / `clear` is a non-atomic check-then-act on a `ConcurrentHashMap`. Two threads can both observe the overflow and both `clear()`, discarding formatters that other threads are about to reuse. The map itself stays structurally sound (no corruption), and `DateTimeFormatter` is immutable and thread-safe — so the blast radius is a performance regression, not a correctness failure. The pattern string is also attacker-influenced if it ever comes from a request.
**Severity:** LOW.

#### SL-22 — `BoundedAsyncAuditEventWriter` silently discards audit events under back-pressure
`utils/common-logging/src/main/java/com/shop/common/logging/audit/BoundedAsyncAuditEventWriter.java:39-49, 119-124`
```java
static final int CORE_POOL_SIZE = 2;
static final int MAX_POOL_SIZE = 4;
static final int QUEUE_CAPACITY = 1000;
...
private void onRejection() {
    long total = discarded.incrementAndGet();
    if (total == 1 || total % WARN_EVERY == 0) {
        log.warn("audit: queue overflow — discarded {} audit events total", total);
    }
}
```
**Unsafe construct:** the design is deliberate and documented, and it is **correctly implemented** (the counters are `AtomicLong`, the `FileSink.accept` is `synchronized`, the `recordSinkFailure` throttle uses a correct CAS at lines 130-134). The defect is the *policy*: an audit library **discards audit events** when the queue fills, with only a WARN every 100th. `BoundedAsyncAuditEventWriterTest.java:77-110` pins this behaviour. There is no metric export and no alert on `discardedEvents()`.
A secondary effect of the `ThreadPoolExecutor` semantics: with `core=2`, `max=4` and a **bounded** queue of 1000, the pool does **not** grow to 4 threads until the queue is already full — so "max 4" is effectively unreachable during normal operation.
**Severity:** MEDIUM (audit-trail completeness under load).
**Thread-safety verdict for this class: SAFE.** `executor` is a `ThreadPoolExecutor`; `discarded`/`failed`/`lastSinkWarnNanos` are `AtomicLong`; `FileSink.accept` is `synchronized` (line 181); the lazily-opened `writer` field is only touched inside that monitor; the rejection handler never throws; `write()` never blocks the caller.

#### SL-23 — `AuditActorResolver` treats an `AnonymousAuthenticationToken` as an authenticated caller
`utils/common-logging/src/main/java/com/shop/common/logging/audit/AuditActorResolver.java:44-49`
```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication == null || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof Jwt jwt)) {
    return ANONYMOUS;
}
```
**Unsafe construct:** Spring Security's `AnonymousAuthenticationToken.isAuthenticated()` returns **`true`**, so an anonymous request passes the `isAuthenticated()` gate. It is then correctly demoted to `ANONYMOUS` by the `instanceof Jwt` check, so the *outcome* is right — but the guard as written does not actually mean "authenticated". A future edit that relaxes the `instanceof Jwt` clause would silently start labelling anonymous callers as real users. The `AuditActorResolverTest.java:101-109` covers a non-JWT principal but not the anonymous-token case.
**Severity:** LOW (latent).

#### SL-24 — `ServiceCallerGuard.requireCaller()` **fails open** for an unauthenticated caller
`utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java:27-37`
```java
public void requireCaller(String... allowedClientIds) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
        // No authentication at all: the endpoint's own @PreAuthorize is the
        // authority on whether the request may proceed, ...
        return;
    }
    if (!authentication.isAuthenticated()) {
        throw BusinessException.of(ErrorCode.FORBIDDEN, "caller is not authenticated");
    }
```
**Unsafe construct:** `SecurityContextHolder.getContext().getAuthentication()` returns **`null`** for an unauthenticated request — so the *fail-open* branch at lines 29-34 is the **normal** unauthenticated path, and the guard **allows** the call. The comment defers to "the endpoint's own `@PreAuthorize`", but the guard is offered by the shared library as a reusable service-caller check; any caller that relies on it alone gets no protection. `BaseSecurityConfig.java:83` (`anyRequest().authenticated()`) covers the default chain, but the guard itself is not fail-closed.
**Severity:** HIGH (fail-open authorization primitive in a shared library).

#### SL-25 — `ServiceCallerGuard` lets any holder of a realm ADMIN/MANAGER role bypass the client check entirely
`ServiceCallerGuard.java:18, 38-40, 61-66`
```java
private static final List<String> HUMAN_ADMIN_ROLES = List.of("ADMIN", "MANAGER");
...
if (hasHumanAdminRole(authentication)) {
    return;
}
```
**Unsafe construct:** the realm role check uses `authentication.getAuthorities()`, which is populated from `realm_access.roles` by `JwtRolesConverter` (`JwtRolesConverter.java:36-46`). A human admin therefore bypasses the `azp` allow-list — including on internal service-to-service endpoints that were meant to be callable only by named clients.
**Severity:** MEDIUM (privilege breadth).

#### SL-26 — `AuthenticatedUser.from(Jwt)` takes its authorities from the ambient context, not from the argument
`utils/common-security/src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java:35-47, 86-89`
```java
public static AuthenticatedUser from(Jwt jwt) {
    List<GrantedAuthority> granted = currentAuthorities().stream()
            .map(GrantedAuthority.class::cast)
            .toList();
    return new AuthenticatedUser(JwtClaimExtractor.subject(jwt), ..., granted);
}
...
private static Collection<? extends GrantedAuthority> currentAuthorities() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth == null) ? List.of() : auth.getAuthorities();
}
```
**Unsafe construct:** the method's contract is *"Build from a verified JWT"*, but the `authorities` component comes from `SecurityContextHolder` — the **ambient** principal of the current thread, which may be a different subject than the `jwt` argument. The record then mixes claims from one principal with authorities from another.
**Severity:** MEDIUM (correctness of an authorization-relevant value object).

#### SL-27 — `SecurityProperties` null-guards `expectedAudiences` but not `expectedClientIds`
`utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java:120-131`
```java
public SecurityProperties {
    if (publicPaths == null) { publicPaths = List.of(); }
    if (expectedAudiences == null) { expectedAudiences = List.of(); }
    if (cors == null) { cors = new Cors(...); }
}
```
**Unsafe construct:** `expectedClientIds` has **no** null guard, yet `BaseSecurityConfig.buildValidatorChain()` dereferences it at line 135 (`properties.expectedClientIds().isEmpty()`) during **bean creation**. A null binding ⇒ `NullPointerException` at startup, not a validated failure. (`@DefaultValue` makes the null case unlikely in practice, which is exactly why the asymmetry is a trap.)
**Severity:** LOW (latent NPE at startup).

#### SL-28 — `ObjectStorageAutoConfiguration` performs a **network call inside a `@Bean` method**
`utils/common-storage/src/main/java/com/shop/common/storage/config/ObjectStorageAutoConfiguration.java:63-72`
```java
@Bean
@ConditionalOnMissingBean
public ObjectStorageService objectStorageService(S3Client s3Client, S3Presigner s3Presigner, StorageProperties props) {
    S3ObjectStorageService service = new S3ObjectStorageService(s3Client, presigner, props);
    if (props.isAutoCreateBucket()) {
        service.ensureBucketExists(props.getBucket());
    }
    return service;
}
```
**Unsafe construct:** bean creation now depends on the object store being reachable. `S3ClientFactory.s3Client()` (lines 30-37) sets **no** `ClientOverrideConfiguration` and therefore **no API-call timeout**, so an unreachable store blocks the startup thread indefinitely. A second effect: two replicas starting concurrently both `headBucket` → both `createBucket` → the loser receives `BucketAlreadyOwnedByYou`/409, which `createBucket` (lines 65-72) turns into a `StorageException` → **startup failure**.
**Severity:** HIGH (availability + startup race).

#### SL-29 — `S3ObjectStorageService.download()` buffers up to 50 MB per call
`utils/common-storage/src/main/java/com/shop/common/storage/service/S3ObjectStorageService.java:110-124`
```java
private static final int MAX_BUFFER_BYTES = 50 * 1024 * 1024;
...
byte[] body = stream.readNBytes(MAX_BUFFER_BYTES + 1);
if (body.length > MAX_BUFFER_BYTES) {
    throw new StorageException("Object exceeded max in-memory download buffer limit of " + ...);
}
```
**Unsafe construct:** the guard is correct (the advertised `contentLength` short-circuits at line 118, and the `readNBytes` bound catches a lying/absent header), but the ceiling itself is 50 MB **per concurrent request**. N concurrent downloads ⇒ N × 50 MB of heap.
**Severity:** MEDIUM.

#### SL-30 — Five `CommonProperties` toggles are dead configuration
`utils/common-spring/src/main/java/com/shop/common/spring/config/CommonProperties.java:35-43, 85-147`
```java
public record CommonProperties(
        @Valid @DefaultValue Service service,
        @Valid @DefaultValue Security security,
        @Valid @DefaultValue Keycloak keycloak,
        @Valid @DefaultValue Logging logging,
        @Valid @DefaultValue Kafka kafka,
        @Valid @DefaultValue Storage storage,
        @Valid @DefaultValue Defaults defaults
)
```
**Unsafe construct:** `security().enabled()`, `keycloak().enabled()`, `kafka().enabled()`, `storage().enabled()` and `logging().correlationId()` are read **nowhere in production code** — verified by grep: the only reader is `CommonLibraryStarterTests.java:73-76`. The real gates are different properties entirely:
| Dead toggle (`shop.common.*`) | Actual gate that does the work |
| --- | --- |
| `shop.common.security.enabled` | `shop.security.enabled` (`SecurityAutoConfiguration.java:29`) |
| `shop.common.keycloak.enabled` | `shop.keycloak.admin-username` / `admin-client-secret` (`KeycloakAutoConfiguration.java:85-86`) |
| `shop.common.kafka.enabled` (default **false**) | `shop.kafka.enabled` (default **true**, `KafkaProperties.java:25`, `KafkaAutoConfiguration.java:45` `matchIfMissing=true`) |
| `shop.common.storage.enabled` (default **false**) | `shop.storage.enabled` (default **true**, `StorageProperties.java:29`, `ObjectStorageAutoConfiguration.java:24` `matchIfMissing=true`) |
| `shop.common.logging.correlation-id` | `shop.web.correlation.enabled` (`WebAutoConfiguration.java:30-31`) |
The class javadoc (lines 22-23) claims these let *"operators flip a single switch that all services honour"* — they do not. The worst case is `shop.common.kafka.enabled: false` in the shared `application.yml` (line 216) while Kafka auto-configuration is **on by default**: an operator who sets it to `true` gets nothing, and an operator who believes it is `false` is wrong.
**Severity:** MEDIUM (misleading platform knobs).

#### SL-31 — `Messages` is a static global facade
`utils/common-core/src/main/java/com/shop/common/core/i18n/Messages.java:32, 38-40`
```java
private static volatile MessageSource messageSource;
public static void setMessageSource(MessageSource source) {
    messageSource = source;
}
```
**Verdict: safe.** The field is `volatile` (correct publication) and is written exactly once, by `I18nAutoConfiguration.i18nStaticBridge()` (lines 62-65), a `SmartInitializingSingleton` that runs after all singletons exist. The only caveat is that in a JVM hosting **two** Spring contexts (test suites), the last one to initialise wins globally.
**Severity:** LOW (design note, not a defect).

#### SL-32 — `common-core`'s `Messages.fromBundle` uses `ResourceBundle.getBundle`, whose default-locale fallback can leak a wrong locale
`Messages.java:63-73`
```java
ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASENAME, locale);
```
**Unsafe construct:** `ResourceBundle.getBundle(String, Locale)` uses the **default control**, which falls back to `Locale.getDefault()` when the requested bundle is missing — so a request for `fr` can be answered with the JVM-default locale's bundle rather than the `default-locale` configured by `I18nProperties`. The Spring path (`source.getMessage`, line 55) does not have this behaviour, so the two resolution paths in the same class disagree. `ResourceBundle` also uses a JVM-global static cache.
**Severity:** LOW (i18n correctness, non-obvious).

### 4.3 Objects that were checked and found SAFE (explicitly)

Reporting "it looks fine" with evidence, as required:

- **`KafkaMessagePublisher.publishBatch`** (`KafkaMessagePublisher.java:165-222`) — the highest-risk method in the audit (fan-out, callbacks on a foreign thread, timeouts). It is **correct**: `AtomicInteger sent/success` (`:169-170`), `CountDownLatch` (`:171`), `AtomicReferenceArray<BatchOutcome.RecordOutcome>` (`:172-173`), per-index writes (`:187`, `:195`, `:197`) that are index-aligned and therefore race-free, and `latch.countDown()` **after** the outcome write (`:200`) so `await()` returning `true` happens-after every write. `latch.await(timeout, unit)` returning `false` is handled explicitly (`:213-219`) and the still-in-flight records are reported as failures. The `CountDownLatch` also establishes the needed happens-before edge for the `AtomicReferenceArray` reads at `:212`.
- **`KafkaMessagePublisher.traceparentRecord`** (`:239-246`) — `Context.current()` is read on the **calling** thread, so the trace context is captured at enqueue time, not at completion time. `HEADER_SETTER` (`:82-87`) is a stateless static lambda; `Headers` is per-record. `W3CTraceContextPropagator` is immutable. **Safe.**
- **`BoundedAsyncAuditEventWriter`** — see SL-22 verdict: **safe**.
- **`LazyJwtDecoder`'s publication mechanism** — `AtomicReference` + CAS is correct; only the *cost* of the losing path is wrong (SL-03).
- **`TraceparentRestClientBuilderPostProcessor`** (`TracingAutoConfiguration.java:77-122`) — `interceptor` is an `AtomicReference` (`:82`); `beanFactory` is a plain field written by `setBeanFactory` (`:87-89`) during Spring's own bean initialisation, which publishes the BPP safely through the `BeanFactory`'s synchronised registry. The `instanceof` gate (`:96`) makes the enrichment idempotent, pinned by `TracingAutoConfigurationTest.java:176-187`.
- **`MdcOtelCurrentTraceContext`** (`:17-45`) — MDC is a thread-local map; OTel scopes are thread-confined; the decorator restores the parent context on close (`:31-34`) and removes the keys when the parent is null (`:38-41`). Pinned by `TracingAutoConfigurationTest.java:70-84`. **Safe.**
- **`AuditEvent.toJson()`** (`AuditEvent.java:42-81`) — hand-rolled escaping, but correct: it escapes `"`, `\`, `\n`, `\r`, `\t` and every control character `< 0x20` as `\uXXXX`. No injection vector. Pinned by `AuditEventTest.java:46-55`.
- **`ServiceCallerGuard.clientIdOf`** (`:54-59`) — reads the `azp` claim only; no trust of a request header.

### 4.4 External-library thread-safety, verified against the dependency source (not assumed)

- **`ModelMapper` 3.2.6 singleton** (`ModelMapperAutoConfiguration.java:27-35`) — I did **not** assume it is unsafe. The 3.2.6 sources show: `TypeMapStore.typeMaps` is a `ConcurrentHashMap` (`org/modelmapper/internal/TypeMapStore.java:35`), `getOrCreate` is guarded by `synchronized (lock)` (`:121`), and `TypeMapImpl.mappings` is a `TreeMap` guarded by `synchronized (mappings)` (`org/modelmapper/internal/TypeMapImpl.java:91, 120, 169, 355, 362`). **The shared singleton is thread-safe.** The residual issue is *contention* (all mappings for one type pair serialise on one monitor), not correctness — LOW/performance.
- **AWS SDK v2 `S3Client` / `S3Presigner`** — the AWS SDK v2 clients are documented as thread-safe and are designed as singletons; `S3ObjectStorageService` holds them as `final` fields (`S3ObjectStorageService.java:35-36`). **Safe.**
- **Jackson `ObjectMapper`** — a configured `ObjectMapper` is thread-safe for concurrent reads; `WIRE_MAPPER` (`:39-40`) is configured once at class-init and never mutated afterwards. **Concurrency-safe** (its defect is schema strictness — SL-07).
- **`NimbusJwtDecoder`** — thread-safe; its `RemoteJWKSet` caches the JWKS and refreshes it on an unknown `kid`, so **JWKS caching and rotation are handled by the library** — there is no missing JWKS-cache defect to report here.
- **`SimpleClientHttpRequestFactory`** (`KeycloakAutoConfiguration.java:43-45`) — `HttpURLConnection`-based, so **no connection pooling**: one TCP connect + TLS handshake per Keycloak call. Under load this is a throughput problem, not a correctness problem. LOW/performance.

### 4.5 Singleton-creation-order and lifecycle notes

- `AuditAutoConfiguration.auditEventWriter()` (`:34-38`) returns the `AuditEventWriter` **interface** type. Spring's inferred destroy-method lookup resolves `close` on that type (`AuditEventWriter` declares `close()` at line 20 and extends `AutoCloseable`), so **`close()` IS called on context shutdown** and the executor is drained within 5 s (`BoundedAsyncAuditEventWriter.java:148-158`). **No leak.** Verified by `AuditAutoConfigurationTest.java:26-33` (bean present) and the writer's own `close()` contract.
- `ObjectStorageAutoConfiguration` declares `S3Client` (`:28-33`) and `S3Presigner` (`:35-40`) beans without an explicit `destroyMethod`; Spring's inference finds `close()` on both (`SdkAutoCloseable`), so the HTTP connection pools are released. **No leak.**
- `KeycloakAutoConfiguration` creates `RestClient.builder()` **inline** (`:62`, `:89`) rather than injecting the context's `RestClient.Builder` bean. The class javadoc (`:29-41`) explains *why* for the tracing seam, but the same choice also silently bypasses every other application-level `RestClient` customisation (custom request factory, interceptors, Boot's `RestClientCustomizer` beans, Boot's observation instrumentation). **HIGH** for operability — see SL-33.

### 4.6 Deadlock / lock-ordering analysis

Only two JVM monitors are taken in the scope: `KeycloakAdminClient`'s `synchronized (this)` (`:408`) and `BoundedAsyncAuditEventWriter.FileSink`'s `synchronized accept` (`:181`). Neither is held while acquiring another lock, and no lock is acquired in a cycle, so **no lock-ordering deadlock exists**. The `FileSink` monitor is a leaf lock held for the duration of one file write. The `KeycloakAdminClient` monitor is held across network I/O (SL-01) — a *stall*, not a deadlock, but it converts an IdP slowdown into a service-wide stall on admin operations.

---

## 5. Kafka library analysis

### 5.1 Producer configuration — the actual values

`KafkaProperties.buildProducerProperties()` (`KafkaProperties.java:80-96`), verbatim:
```java
Map<String, Object> props = new HashMap<>();
props.put("bootstrap.servers", bootstrapServers);
props.put("acks", producer.getAcks());
props.put("retries", producer.getRetries());
props.put("max.in.flight.requests.per.connection", 1);
props.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
props.put("enable.idempotence", producer.isEnableIdempotence());
props.put("compression.type", producer.getCompressionType());
props.put("batch.size", producer.getBatchSizeBytes());
props.put("linger.ms", producer.getLingerMs());
```
Defaults (`KafkaProperties.java:153-174`): `acks="all"`, `retries=3`, `enableIdempotence=true`, `compressionType="lz4"`, `batchSizeBytes=32768`, `lingerMs=5`.
The value serializer is added by `KafkaAutoConfiguration.kafkaProducerFactory` (`:51-57`) — `JsonKafkaSerializer` for the generic template, `StringSerializer` for `stringKafkaTemplate` (`:70-77`).

### 5.2 Exactly-once — the precise answer

**Exactly-once is NOT implemented.** What *is* implemented is **idempotent production**:
- `enable.idempotence=true` + `acks=all` + `max.in.flight.requests.per.connection=1` + `retries=3` — the canonical "exactly-once *per producer session*" recipe. This de-duplicates broker-side retries within a producer's lifetime (broker dedup by producer id + sequence number).
- There is **no** `transactional.id`, **no** `KafkaTransactionManager`, and **no** producer/consumer transaction template anywhere in `common-kafka` (verified by grep over the module). Therefore there is **no** atomic read-process-write between the database and the broker.
- The database-side half of the guarantee is supplied by the **outbox pattern** in the *services* (`OutboxStatus` in `common-core`; `OutboxBatchOrdering` in `common-kafka`), which gives **at-least-once** delivery with per-row idempotent marking.
- `KafkaProperties.Retry`'s own javadoc (`:109-127`) is honest that the retry/DLT block is **INTENTIONALLY UNUSED**, because the fleet's listener factory uses a raw-String deserializer and an "ack-always poison posture".
**Verdict: producer idempotence = YES; exactly-once = NO.** The label "exactly-once" in `KafkaPropertiesProducerDefaultsTest.java:31-41` ("KeepsAcksAllAndExactlyOnceByDefault") is a **misnomer** — the test name overstates what the configuration delivers.

### 5.3 Topics, consumer groups and partition keys

`KafkaTopicContract` (`KafkaTopicContract.java:37-148`) is a genuine, enforced registry — and one of the better artefacts in the codebase. It declares 10 topics (`:85-117`), each with a `Mode` (`CONSUMED` / `FIRE_AND_FORGET`), the owning producer module, and — for fire-and-forget topics — a **mandatory rationale** enforced by the record's compact constructor (`:60-64`). `BY_TOPIC` (`:119`, `:124-133`) is built eagerly and rejects duplicates with `IllegalStateException` (`:129`).
The four `FIRE_AND_FORGET` topics carry explicit justifications (`:99-117`), e.g. the inventory topic explains that the checkout flow reads inventory **synchronously**, so no in-fleet consumer is required for correctness.
The contract is enforced by `KafkaTopicContractTest.java:57-120` (four directions: every consumed topic declared, every `CONSUMED` topic really has a listener, every fire-and-forget topic justified, every declared topic really produced) plus `KafkaTopicContractTest.java:122-149` (no undeclared topic literal anywhere).

**Partition keys** are the **caller's** responsibility, not the library's. `KafkaMessagePublisher.publish(topic, key, value)` (`:110-123`) takes the key verbatim and passes it to `new ProducerRecord<>(topic, key, value)` (`:240`). The library does **not** derive or validate the key. The ordering contract is documented and enforced *outside* the library by `OutboxBatchOrdering.markableAsSent` (`:39-57`), which blocks the later records of the **same aggregate** when one of them fails — its rule: "within one aggregate, a record that failed blocks every later record of the SAME aggregate" (`:17-20`), pinned by `OutboxBatchOrderingTest.java:15-62`.

### 5.4 Consumer configuration, retry and DLT

`BaseKafkaListenerConfig.kafkaListenerContainerFactory()` (`:83-94`):
```java
var factory = new ConcurrentKafkaListenerContainerFactory<K, String>();
factory.setConsumerFactory(rawStringConsumerFactory());
factory.getContainerProperties().setObservationEnabled(true);
if (template != null) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));
} else {
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
}
```
- **Retry/DLT:** `FixedBackOff(1000L, 3L)` = up to **3 retries at 1 s intervals** (4 attempts total), then the recoverer publishes to `<topic>.DLT` (Spring Kafka's default `DeadLetterPublishingRecoverer` destination) — **but only when a `KafkaOperations` template was supplied** to the constructor (`:67-75`). When `template == null` the handler has **no recoverer** → the default `LoggingRecoverer` **logs and skips** the record: **silent record loss**.
- **Deserialization:** the factory installs `StringDeserializer` for **both** key and value (`:96-112`) — deliberately, so poison bytes cannot tombstone a partition at deserialize time (`:38-40`). Typed binding happens in the application layer at the `BaseKafkaConsumer` boundary.
- **Type headers / trusted packages:** there is **no** `JsonDeserializer` on the live path and **no** `spring.json.trusted.packages` configuration anywhere in the module. `JsonKafkaDeserializer` (`:15-52`) binds to a **concrete** target class with a plain `ObjectMapper` — `enable.default.typing` is never enabled, so **there is no polymorphic deserialization gadget surface**. `BaseKafkaConsumer.java:31-32` states this correctly. **No trusted-packages gap exists — because there is no polymorphic typing to restrict.**
- **Container observation** is enabled (`:86`) so Micrometer's propagating handler can extract the `traceparent` header; pinned by `TraceparentHeaderExtractionTest.java:103-146`, which proves the listener span continues the remote parent's trace and that a missing header yields a fresh root trace.

### 5.5 Error handling and the poison-record path

`BaseKafkaConsumer.decodeContained` (`:91-117`): unwrap-once for the legacy double-encoded wire (`:98-101`), bind to the concrete DTO (`:102`), reject a null binding with an explicit `IllegalStateException` (`:103-109`), and on any failure **throw** `PoisonRecordException` (`:114-115`) so the container's `DefaultErrorHandler` retries and then routes to the DLT. The tombstone case (`null` value) is a contained ack-skip (`:92-95`, `BaseKafkaConsumerTypedBindTest.java:93-99`).
The end-to-end DLT path is proven by `BaseKafkaConsumerDeadLetterTest.java:84-94` against an embedded KRaft broker: the poison record reaches the dead-letter topic and the listener **never** sees it.
**Gaps:** (a) the javadoc at `BaseKafkaConsumer.java:47-51` still claims "no DLT" (SL-09); (b) `KafkaProperties.Retry` is dead config (`:109-127`); (c) the no-recoverer path silently skips records (above).

### 5.6 Consumer idempotency

The library **requires** it and does not provide it:
- `OutboxBatchOrdering.java:22-24` — *"This is at-least-once: a blocked record may be published twice. **The consumer must stay idempotent**, which the outbox contract already requires."*
- `KafkaMessagePublisher.java:213-217` — a publish that misses its deadline is reported as a failure and will be retried, *"the retry may duplicate the publish, which the at-least-once contract already allows."*
- `DeadLetterReplayService` makes the duplication **operator-triggerable** — see SL-05.
No idempotency key, dedup store, or inbox table exists in `common-kafka`; idempotency is entirely the consumers' responsibility.

### 5.7 Replay tooling — `DeadLetterReplayService` / `DeadLetterReplayEndpoint`

`DeadLetterReplayEndpoint` is an actuator `@WriteOperation` endpoint with id `deadLetterReplay` (`DeadLetterReplayEndpoint.java:10, 26-40`), gated by `shop.kafka.dlq-replay.enabled=true` (`KafkaAutoConfiguration.java:90`) and `@ConditionalOnClass(Endpoint.class)` (`:89`).
**Mitigating facts (verified):** the shipped `management.endpoints.web.exposure.include` is `health,info,prometheus,metrics` (`application.yml:65-70`), so the endpoint is **not reachable over HTTP** unless an operator adds `deadLetterReplay` to that list. **The shared library nevertheless ships the endpoint with no authorization annotation of any kind** — no `@PreAuthorize`, no role requirement, no operator-confirmation token. If an operator exposes it, anyone who can reach the actuator can re-publish arbitrary DLT records to arbitrary topics named in a `kafka_dlt-original-topic` header — i.e. **a data-replay / mass-duplication primitive**. The library also does not validate that the header's topic is one declared in `KafkaTopicContract` (`DeadLetterReplayService.java:96-103` reads the header and republishes to it verbatim at `:105`).
**Severity: HIGH (defence-in-depth gap in a shared library), currently latent because the endpoint is not exposed by default.**

---

## 6. Security library analysis

### 6.1 JWT validation chain

`BaseSecurityConfig.buildValidatorChain()` (`:122-145`) builds, in order:
1. `JwtIssuerValidator(properties.issuerUri())` (`:124`) — issuer must match exactly.
2. `JwtTimestampValidator()` (`:125`) — `exp`/`nbf` validity.
3. `JwtClaimValidator<Object>("aud", …)` (`:127-133`) — **only if** `expectedAudiences` is non-empty.
4. `JwtClaimValidator<Object>("azp", …)` (`:137-142`) — **only if** `expectedClientIds` is non-empty.

**The aud validator is stricter than RFC 7519.** RFC 7519 §4.1.3 allows `aud` to be *either* a single string *or* an array of strings. The validator accepts only the array form:
```java
if (!(aud instanceof List<?> audiences) || audiences.isEmpty()) {
    return false;
}
```
A token with `"aud": "orders-service"` is **rejected**. This behaviour is deliberately pinned by `BaseSecurityConfigAudienceValidationTest.java:38-43` (`rejectsMalformedAudienceWithoutThrowing`) — so it is *intended*, but it is non-conformant and would break any non-Keycloak issuer or any future audience-mapper that emits a scalar.

**The azp validator is the only usable client discriminator on the provisioned realm.** `application.yml:141-159` documents the measurement: the Keycloak 26 realm ships **no audience mapper and no client scopes**, so its tokens carry **no `aud` claim at all** — only `azp`. `expected-audiences` therefore ships **empty** (`:159`) and the aud validator is not installed. The azp validator *is* installed, with the fleet-wide list
`ecommerce-client, order-service, rating-service, search-service, product-service, media-service` (`:158`).
**Consequence:** every service in the fleet accepts a token issued to *any* of those six clients, unless it overrides the list. The YAML comment says "Narrow it per service where the callers are known" (`:157`) — but the shipped default is the broad one. This is the platform's *only* horizontal-privilege boundary at the JWT layer.

**Verifier hygiene:** the decoder is `NimbusJwtDecoder.withIssuerLocation(...)` (`:116`), i.e. the signature is verified against the issuer's JWKS. There is **no** `alg=none` acceptance and no symmetric-secret fallback. `JwtTimestampValidator` supplies a default clock skew (60 s). Good.

### 6.2 JWKS caching

Handled entirely by the library: `NimbusJwtDecoder.withIssuerLocation(...)` resolves the OIDC discovery document and builds a `RemoteJWKSet`-backed decoder, which **caches the JWKS and re-fetches it when an unknown `kid` is presented** — so key rotation is handled and there is **no missing JWKS-refresh defect**.
The library's contribution is the **acquisition strategy**: `LazyJwtDecoder` (`:36-96`) defers the discovery fetch off the startup path and pre-warms it on a daemon thread (`:62-66`). `BaseSecurityConfigJwtDecoderLazyTest.java:12-36` proves the bean factory completes in < 2 s against an unreachable issuer (`:17` uses `http://127.0.0.1:1/realms/ecommerce`).
**Two gaps in the strategy** (not in the caching itself): the "only one decoder is built" guarantee is false (SL-03), and a failed pre-warm pushes a synchronous discovery onto every request thread (SL-04). There is also **no negative caching** — a permanently broken IdP is re-contacted on every request.

### 6.3 Role and scope extraction

`JwtRolesConverter` (`JwtRolesConverter.java:29-63`) maps `realm_access.roles` into authorities and emits **two** authorities per role — the raw name and the `ROLE_`-prefixed form (`:58-60`) — so `.hasAuthority("admin")` and `.hasRole("admin")` both work. Empty/absent `realm_access` → an empty collection (`:38-44`), which Spring Security treats as "authenticated but unauthorised" (documented at `:24-27`). Roles are trimmed and null-filtered (`:51-57`).
**Scoring:** the converter reads **only** `realm_access.roles` — it does **not** read `scope`/`scp`. So a **scope-based** token (a pure OAuth2 client-credentials token carrying `scope=read:all`) yields **zero** authorities and is treated as authorised for nothing. `BaseSecurityConfig.jwtAuthenticationConverter()` (`:151-156`) is `@ConditionalOnMissingBean`, so a service can substitute a scope-aware converter (documented at `:147-149`) — the extension point exists.
**Minor:** a role already named `ROLE_ADMIN` is emitted twice (`:59-60`).

`JwtClaimExtractor` (`JwtClaimExtractor.java:18-80`) centralises claim names: `sub`, `email`, `preferred_username`, `name`/`given_name`/`family_name`, `realm_access.roles`. Every accessor is null/blank-safe, and `realmRoles` (`:62-75`) returns an immutable snapshot filtered to `String` elements.

### 6.4 Method security

`SecurityAutoConfiguration` declares `@EnableMethodSecurity` (`:31`). The fleet uses it: 38 production files contain `@PreAuthorize` (verified by grep over the repository). The shared library itself contributes **no** `@PreAuthorize`/`@Secured` annotation — with one exception that is a gap:
- **`DeadLetterReplayEndpoint` has no method-security annotation** (see §5.7).
- **`CorsAutoConfigurer.corsConfigurationSource`** has no `@ConditionalOnMissingBean` (`:31-33`), so a service that wants its own `CorsConfigurationSource` must **replace** the bean, which fails under Boot's default `allow-bean-definition-overriding=false`. **MEDIUM.**

### 6.5 Trust of client-supplied headers

This is the weakest area of the security library, and it is a **shared** weakness because both helpers live in `common-spring` and are used by every service:

| Header | Trusted? | Evidence | Impact |
| --- | --- | --- | --- |
| `X-Correlation-Id` | **Yes, verbatim, unvalidated** | `CorrelationIdFilter.java:39-43, 61-64` | Written into the MDC (`correlationId`, `requestId`, `traceId`) and echoed in the response header. No length cap, no charset whitelist ⇒ **log forging**, log bloat, and trace-id spoofing. **HIGH.** |
| `X-Request-Id` | **Yes, verbatim, unvalidated** | `CorrelationIdFilter.java:40, 43` | Same. |
| `X-Forwarded-For` / `X-Original-Forwarded-For` / `X-Real-IP` / `Proxy-Client-IP` / `WL-Proxy-Client-IP` | **Yes, first non-empty wins** | `RequestUtils.java:12-18, 27-33` | The client IP is fully attacker-controlled unless a trusted proxy overwrites the header. `RequestUtils.isLocalAddress()` (`:43-45`) is the ready-made bypass primitive. **HIGH** (as public API). |
| `X-Forwarded-*` (Spring's own) | **Yes** | `application.yml:47` — `server.forward-headers-strategy: framework` | Spring's `ForwardedHeaderFilter` trusts `X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-Port` from any source, which can influence redirect generation and absolute-URL generation. |
| `Accept-Language` | Yes, but **bounded** | `I18nAutoConfiguration.java:48-55` — `AcceptHeaderLocaleResolver` with `setSupportedLocales(List.of(defaultLocale, Locale.ENGLISH))` | Unknown locales fall back to the configured default. **Acceptable.** |
| `User-Agent`, `Referer` | Logged verbatim | `HttpLoggingFilter.java:83-84, 95-97` | Log-injection surface again (the same unvalidated-MDC problem, in a second field). **MEDIUM.** |
| `Origin` (CORS) | Bounded by configuration | `application.yml:163-180` | `allowed-origin-patterns` ships an **explicit localhost list** (`http://localhost:3000,http://localhost:5173`) — pinned by `SharedDefaultsSecurityTest.java:47-62` — and `allow-credentials: false`. **Good.** The *code* default in `SecurityProperties.Cors.resolvedAllowedOriginPatterns()` (`:113-117`) is `List.of("*")`, and `CorsAutoConfigurer` only **warns** when `allowCredentials=true` coexists with a wildcard (`:42-45`) — the shipped YAML overrides it, but a service that sets `allow-credentials: true` without setting origins gets `*` + credentials, which the browser then rejects anyway. **MEDIUM (configuration trap).** |

### 6.6 Credential handling in the shipped defaults

Verified from `application.yml` and the tests that pin it:
- **No Keycloak credential default.** `admin-username`/`admin-password` are empty (`:200-201`) and `admin-client-secret` is empty (`:199`). Pinned by `SharedDefaultsSecurityTest.java:31-45`, which asserts the value matches `\$\{[A-Z_]+:\}` — an empty placeholder.
- **No object-store credential default.** `StorageProperties.accessKey`/`secretKey` default to `""` (`StorageProperties.java:47, 54`) and `ObjectStorageAutoConfiguration.requireCredentials` (`:50-61`) **fails the startup** with a named message. Pinned by `StoragePropertiesCredentialsTest.java:21-55`.
- **No shipped realm secret.** `RealmImportCredentialContractTest.java:88-149` asserts every client secret and every seeded password in `docker/keycloak/import/ecommerce-realm.json` is a `${VAR}` placeholder and that the bootstrap script substitutes every one of them.
- **No stack traces to the client.** `application.yml:55-60` sets `include-message: never`, `include-stacktrace: never`, `include-exception: false`, `include-binding-errors: always`; pinned by `ServerErrorDefaultsTest.java:40-52`. `ApiExceptionHandler` emits a curated generic message for 5xx (`:268-280`) — pinned by `ServerErrorDefaultsTest.java:54-71` and `ApiExceptionHandlerConstraintLeakTest.java:20-48`.
**This is the strongest part of the shared security posture and deserves to be said plainly.**

### 6.7 Authorization primitives

- `ServiceCallerGuard.requireCaller(String... allowedClientIds)` (`ServiceCallerGuard.java:27-51`) — checks the `azp` claim against an allow-list, with a realm-role bypass for `ADMIN`/`MANAGER` (`:18, 38-40`). Two defects: it **fails open** for an unauthenticated caller (SL-24) and the admin-role bypass is broad (SL-25).
- `AuthenticatedUser` (`AuthenticatedUser.java:21-89`) — an immutable snapshot of the principal, with `current()`, `requireCurrent()`, `hasRole`, `hasAnyRole`. Its `from(Jwt)` mixes claims from the argument with authorities from the ambient context (SL-26).
- `BaseSecurityConfig.securityFilterChain` (`:54-89`) — the default chain: CSRF disabled when `csrfDisabled` (default true, `:60`), `SessionCreationPolicy.STATELESS` (default true, `:61`), the service's own `publicPaths` (`:73-79`), the **platform defaults** (`:81-82`), then `anyRequest().authenticated()` (`:83`).

### 6.8 Platform default public paths — audited

`SecurityProperties.PlatformDefaults.PUBLIC_PATHS` (`:145-156`):
```
/actuator/health, /actuator/health/**, /actuator/info, /actuator/prometheus,
/v3/api-docs, /v3/api-docs/**, /swagger-ui.html, /swagger-ui/**,
/swagger-resources/**, /webjars/**
```
The list is **narrow** — no `/actuator/env`, `/actuator/configprops`, `/actuator/heapdump`, `/actuator/loggers`. That is a good outcome and worth stating explicitly, because the opposite is the common mistake. Two observations:
- `/actuator/prometheus` is anonymous by default across the fleet. Prometheus metrics expose route names, URI patterns and JVM internals — an **information-disclosure surface**, though a mild and conventional one. The actuator exposure list (`application.yml:70`) matches it exactly.
- `/v3/api-docs/**` and `/swagger-ui/**` expose the **complete API surface** anonymously. That is a deliberate trade-off, but it means the API inventory of every service is public.
The class comment at `:141-143` states the rule ("Keep this list narrow: any new entry here is anonymous-accessible across the entire fleet") — good hygiene, honoured in practice.

---

## 7. Design-pattern evaluation for the shared libraries

Each pattern below is judged on **real, evidenced usage** — not on the presence of a class name.

### 7.1 Singleton — **PRESENT (two variants), one leak**

| Instance | Mechanism | Thread-safe? | Evidence |
| --- | --- | --- | --- |
| `ModelMapper` | Spring singleton bean | **Yes** (verified against the 3.2.6 sources) | `ModelMapperAutoConfiguration.java:25-35` |
| `ObjectMapper` (Jackson 2) | Spring singleton bean | **Yes** (configured once) | `Jackson2ObjectMapperAutoConfiguration.java:25-29` |
| `S3Client` / `S3Presigner` | Spring singleton bean | **Yes** (AWS SDK v2 clients are thread-safe) | `ObjectStorageAutoConfiguration.java:28-40` |
| `SecurityFilterChain` | Spring singleton bean | **Yes** | `BaseSecurityConfig.java:54-89` |
| `RestClient` (Keycloak) | Held as a final field of a singleton client | **Yes** | `KeycloakAdminClient.java:45, 102` |
| `Messages` | **Static global singleton facade** | **Yes** (`volatile` field, single write) | `Messages.java:32, 38-40` |
| `KafkaMessagePublisher` | Spring singleton bean | **Yes** | `KafkaAutoConfiguration.java:97-102` |
| `BoundedAsyncAuditEventWriter` | Spring singleton bean wrapping a bounded pool | **Yes** | `AuditAutoConfiguration.java:34-38` |

The classic "static mutable singleton" hazard appears **exactly once**: `Messages.messageSource` (`Messages.java:32`) — a `static volatile` field with a public setter. It is correctly published and written once, so it is not a race, but it is a **global** that two Spring contexts in the same JVM would fight over.

### 7.2 Factory — **PRESENT, and genuinely load-bearing**

| Factory | What it builds | Why the pattern is justified |
| --- | --- | --- |
| `S3ClientFactory` (`S3ClientFactory.java:18-49`) | `S3Client`, `S3Presigner`, `StaticCredentialsProvider` | The javadoc states the intent precisely: *"Kept as a small factory so callers can swap in their own credential provider without rewriting the auto-configuration"* (`:13-16`). The seam is real: `ObjectStorageAutoConfiguration` calls the factory but exposes the `S3Client`/`S3Presigner` beans with `@ConditionalOnMissingBean` (`:28-40`), so a service can substitute a different client entirely. **Justified.** |
| `BoundedAsyncAuditEventWriter.create()` / `create(path)` (`:85-95`) | The writer with a stdout or file sink | The sink is injected through a `Consumer<String>` constructor (`:64`), which is exactly what makes `BoundedAsyncAuditEventWriterTest.java:112-165` able to simulate a failing and a recovering sink. **Justified** — it is a testable seam, not decoration. |
| `CommonLibraryStarter.main()` (`:30-32`) | the Spring application | Bootstrap only. |
| `KeycloakAdminClient` / `KeycloakTokenClient` constructors | the clients | These are **not** factories — they are ordinary constructors with 7–10 parameters. The parameter explosion is the real smell here (see §7.8). |

### 7.3 Strategy — **PRESENT, but only implicitly**

There is exactly one real Strategy seam in the shared libraries, and it is not labelled as such:
- `AuditEventWriter` (`AuditEventWriter.java:10-20`) is the strategy interface; `BoundedAsyncAuditEventWriter` is the default implementation; `AuditAutoConfiguration.java:34-38` (`@ConditionalOnMissingBean`) is the strategy-selection mechanism. `AuditAutoConfigurationTest.java:43-50` proves an alternative implementation wins. **Genuine Strategy.**
- `ObjectStorageService` (`ObjectStorageService.java:14-65`) / `S3ObjectStorageService` is a second one, with the same `@ConditionalOnMissingBean` selection (`ObjectStorageAutoConfiguration.java:64-66`). **Genuine Strategy.**
- `AuditActorResolver` / `AuditResourceResolver` are *static helper classes*, **not** strategies — they cannot be substituted (see §3.2).

### 7.4 Adapter — **PRESENT, and correctly used**

| Adapter | Adapts | Evidence |
| --- | --- | --- |
| `S3ObjectStorageService` | AWS SDK v2 `S3Client` → `ObjectStorageService` | `S3ObjectStorageService.java:31-43`; every SDK exception is translated to `StorageException` (`:85-87, 100-102, 132-138, 148-150, 164-166, 178-180`). **Textbook adapter.** |
| `KeycloakAdminClient` / `KeycloakTokenClient` | Keycloak's REST API → a Java client façade | `KeycloakAdminClient.java:188-225` etc.; `RestClientResponseException` → `KeycloakClientException` (`:205-212`). **Textbook adapter.** |
| `JsonKafkaSerializer` / `JsonKafkaDeserializer` | Jackson ↔ Kafka `Serializer`/`Deserializer` | `JsonKafkaSerializer.java:32-67`, `JsonKafkaDeserializer.java:15-52`. **Textbook adapter** (though the deserializer's exception type is wrong — SL-10). |
| `JwtRolesConverter` | Keycloak's `realm_access.roles` claim → `GrantedAuthority` | `JwtRolesConverter.java:29-46`. **Textbook adapter.** |
| `ErrorCatalog` | a service-local enum → the platform error contract | `ErrorCatalog.java:9-15`; proven by `BusinessExceptionTest.java:10-46`. **Textbook adapter + Open/Closed point.** |

### 7.5 Repository — **ABSENT (correctly)**

There is **no** Repository pattern in the shared libraries — and that is the right call. A repository is a domain-layer port over persistence; `common-core` owns no persistence logic at all. The JPA artefacts it does own (`AbstractMappedEntity`, `SoftDeletable`) are **mapped superclasses** — a template, not a repository. `BaseMapper`/`EntityCreateUpdateMapper` (`common-spring/.../mapper/`) are **MapStruct mappers**, not repositories: they map between a model and a view-model (`BaseMapper.java:7-14`). **No defect; correctly omitted.**

### 7.6 Template Method — **PRESENT and the dominant pattern**

| Base | Hook | Evidence |
| --- | --- | --- |
| `BaseKafkaConsumer<K,V>` | three `processMessage` overloads + the private `decodeContained` | `BaseKafkaConsumer.java:52-81, 91-117`. A service subclasses it and supplies only the handler (`BaseKafkaConsumerDeadLetterTest.java:166-174`). **Genuine Template Method.** |
| `BaseKafkaListenerConfig<K>` | abstract `listenerContainerFactory()`; protected `kafkaListenerContainerFactory()` supplies the template body | `BaseKafkaListenerConfig.java:61-94`. **Genuine Template Method.** |
| `AbstractMappedEntity` / `SoftDeletable` | `markDeleted`/`markRestored` template operations | `SoftDeletable.java:40-52`. **Genuine.** |
| `ApiExceptionHandler` | one `@ExceptionHandler` template per exception family, all funnelling into `buildErrorResponse` | `ApiExceptionHandler.java:54-322`. A **Template Method with an explicit hook table** — the javadoc (`:42-46`) justifies the per-type handlers over a table-driven mapper. **Genuine, and well argued.** |
| `AuditAspect.audit` | the `emit(...)` hook on the success/fail path | `AuditAspect.java:42-70`. **Genuine.** |
| `LoggerAspect` | two `@Around` advice templates | `LoggerAspect.java:36-85`. **Genuine.** |

### 7.7 Cache-Aside — **PARTIALLY PRESENT**

| Cache | Pattern | Assessment |
| --- | --- | --- |
| `KeycloakAdminClient.cachedToken` | `AtomicReference<CachedAdminToken>` with a TTL and a 30 s skew (`KeycloakAdminClient.java:397-404`) | **Genuine Cache-Aside.** Correct double-checked locking, correct expiry, and it is refreshed **in place** on expiry rather than evicted-then-reloaded. **Justified** — it avoids a master-realm token request on every admin call. |
| `DateTimeUtils.FORMATTERS` | `ConcurrentHashMap` keyed by pattern (`DateTimeUtils.java:17, 26-35`) | A **memoisation** cache, not Cache-Aside (there is no loader-and-evict cycle, just `computeIfAbsent`). The eviction at `:31-33` is a check-then-act flush (SL-21). **Acceptable, minor flaw.** |
| `LazyJwtDecoder.delegate` | `AtomicReference` holding a lazily built decoder (`LazyJwtDecoder.java:41, 48-95`) | **Lazy initialisation**, not Cache-Aside. The "cache" never expires and never re-loads after a failure — a failed build is retried on every request (SL-04). |
| `JWT JWKS` | `RemoteJWKSet` inside Nimbus | **Cache-Aside, handled by the library** — cache with refresh-on-unknown-kid. |
| `ModelMapper`'s type maps | `ConcurrentHashMap` + `synchronized` inside the library | Cache-Aside, handled by the library. |

### 7.8 Decorator — **ABSENT as a pattern; one functional equivalent**

There is no `Decorator` in the GoF sense. The one place it would apply — wrapping the Keycloak `RestClient` with tracing — is instead implemented as a **`RestClient.Builder` request interceptor** (`KeycloakAutoConfiguration.java:42-52`) and a **`BeanPostProcessor`** (`TracingAutoConfiguration.java:66-122`), which are *equivalent in effect* but are **not** the Decorator pattern: the interceptor mutates a shared builder rather than wrapping a delegate. The one place a true decorator is used — `BoundedAsyncAuditEventWriter`'s `Consumer<String>` sink chain — is a **Strategy**, not a decorator (the sink is selected, not stacked).

### 7.9 Genuinely-justified *proposals* (not present today)

These are the patterns a reviewer should **propose**, with the evidence for why each would help:

1. **Ports for the Kafka boundary** — a `MessagePublisher` interface over `KafkaMessagePublisher` (currently a concrete class welded to `KafkaTemplate`, `KafkaMessagePublisher.java:73, 89`) and a `MessageHandler` interface over `BaseKafkaConsumer` (currently an abstract class welded to `MessageHeaders`, `BaseKafkaConsumer.java:52, 60, 72`). Justified by §3.1 L3 and by the fact that `KafkaMessagePublisherTest.java:42` must mock a Spring Kafka type to test the publisher at all.
2. **A `TracePropagator` port** — the W3C injection logic exists in **three** copies (`KeycloakAutoConfiguration.java:42-52`, `KafkaMessagePublisher.java:79-87`, `common-spring/.../TraceparentInterceptor.java:22-35`) because the shared implementation lives in the module that depends on the other two. Extracting it into a leaf module (or into `common-core`) would remove the duplication.
3. **A `Clock` port** — `Instant.now()` is called directly in at least seven places in scope (`SoftDeletable.java:43`, `AuditEvent` construction at `AuditAspect.java:58`, `KeycloakAdminClient.java:151, 404, 410, 437`, `AuditEvent.java:…`), and `System.currentTimeMillis()` in `LoggerAspect.java:43, 46, 54` and `HttpLoggingFilter.java:68, 72`. No clock is injectable, so time-dependent behaviour (token expiry skew, the 60 s audit-warn throttle) cannot be tested deterministically.
4. **A `HttpClient` port for Keycloak** — the `RestClient` is built inline (`KeycloakAutoConfiguration.java:62, 89`) and is not the context's `RestClient.Builder`, so every application-level customisation is bypassed. Injecting the context's builder (or a dedicated `RestClient.Builder` qualifier) would fix SL-33 and re-enable the shared `traceparentRestClientBuilderPostProcessor`.
5. **A `SecurityContext`/`Principal` port for the audit actor** — `AuditActorResolver` is a static helper (`AuditActorResolver.java:30, 41-42`) and cannot be substituted, so a service that needs "the delegating admin" or "the on-behalf-of actor" must fork the class. Making it an interface with a default implementation would let `AuditAspect` depend on the interface (`AuditAspect.java:36-40`).
6. **A `TenantContext`/`RequestContext` port** — `AuditResourceResolver` reads `RequestContextHolder` statically (`AuditResourceResolver.java:3-4, 27`), so the audit resource id is unreachable in a non-servlet context (the javadoc at `:16-17` admits this). A pluggable resolver would let the audit trail work in Kafka consumers and scheduled jobs.

### 7.10 Patterns that are **NOT** present (and should not be added)

- **Flyweight** — `KafkaTopicContract.TOPICS`/`BY_TOPIC` (`:85-133`) is an immutable shared registry, but it is a *value catalogue*, not a flyweight pool of interchangeable objects. **Not applicable.**
- **Builder** — `S3Client.builder()` / `PutObjectRequest.builder()` are the **AWS SDK's** builders, not the platform's. The platform's own `KeycloakAdminClient` instead uses a **10-argument constructor** (`KeycloakAdminClient.java:92-112`) — a constructor-overload smell that a Builder would fix. **Proposed, not present.**
- **Mediator / Observer** — the `AuditEventWriter` hand-off is a one-way push, not a mediator; there is no observer registry.
- **State** — `OutboxStatus` (`common-core`) is an enum used by *service* code, not a state-pattern implementation (no state objects, no transitions). **Not applicable.**

---

## 8. Defect list

### 8.1 Summary

**45 defects: 0 CRITICAL, 14 HIGH, 20 MEDIUM, 11 LOW.**

No CRITICAL was assigned. The shared libraries are, on the whole, well-built — credentials fail fast (`StoragePropertiesCredentialsTest`, `SharedDefaultsSecurityTest`), error bodies are curated (`ApiExceptionHandlerConstraintLeakTest`, `ServerErrorDefaultsTest`), the Kafka topic contract is machine-enforced (`KafkaTopicContractTest`), and the JWT decoder no longer blocks startup (`BaseSecurityConfigJwtDecoderLazyTest`). Every HIGH below is a real defect with a concrete failure mode, not a style objection.

| ID | Sev | Area | One-line | Primary evidence |
| --- | --- | --- | --- | --- |
| SL-01 | **HIGH** | Thread-safety | `synchronized (this)` on a shared bean held across a Keycloak HTTP call | `KeycloakAdminClient.java:402-449` |
| SL-02 | **HIGH** | Security | Admin client silently downgrades to the master-realm *password* grant | `KeycloakAdminClient.java:118-121, 414-422` |
| SL-05 | **HIGH** | Kafka | DLT replay uses a new consumer group + `earliest` ⇒ re-replays and duplicates events | `DeadLetterReplayService.java:71-77, 87-124` |
| SL-08 | **HIGH** | Kafka | Consumer `ObjectMapper` rejects unknown properties (Boot disables it) ⇒ schema evolution DLTs every record | `BaseKafkaConsumer.java:39-40, 102` |
| SL-14 | **HIGH** | Security | Unvalidated client `X-Correlation-Id` written into the MDC and echoed back | `CorrelationIdFilter.java:39-50, 61-64` |
| SL-15 | **HIGH** | Security | Client IP taken from spoofable proxy headers; `isLocalAddress` is a ready-made bypass | `RequestUtils.java:12-18, 27-35, 43-45` |
| SL-16 | **HIGH** | Thread-safety | `ContentCachingResponseWrapper` buffers the whole response body per request | `HttpLoggingFilter.java:64-74` |
| SL-24 | **HIGH** | Security | `ServiceCallerGuard` **fails open** for an unauthenticated caller | `ServiceCallerGuard.java:27-37` |
| SL-28 | **HIGH** | Thread-safety | Network call inside a `@Bean` method + bucket-creation startup race | `ObjectStorageAutoConfiguration.java:63-72` |
| SL-33 | **HIGH** | Config | Keycloak clients built from an inline `RestClient.builder()`, bypassing every customiser | `KeycloakAutoConfiguration.java:62, 89` |
| SL-34 | **HIGH** | Security | DLT-replay actuator endpoint ships with **no** authorization | `DeadLetterReplayEndpoint.java:10, 26-40` |
| SL-35 | **HIGH** | Kafka | Listener factory built without a recoverer ⇒ records are logged and **skipped**, not dead-lettered | `BaseKafkaListenerConfig.java:87-92` |
| SL-36 | **HIGH** | Kafka | Consumer config never pins `enable.auto.commit` / `isolation.level` / poll limits | `KafkaProperties.java:98-106` |
| SL-37 | **HIGH** | Security | Fleet-wide `expected-client-ids` list ⇒ no horizontal-privilege boundary by default | `application.yml:158` |
| SL-03 | MEDIUM | Thread-safety | `LazyJwtDecoder` builds N decoders concurrently; the javadoc's guarantee is false | `LazyJwtDecoder.java:80-95` (claim at `:32-33`) |
| SL-04 | MEDIUM | Thread-safety | Failed JWKS pre-warm ⇒ a synchronous IdP discovery on **every** request thread | `LazyJwtDecoder.java:62-95` |
| SL-06 | MEDIUM | Kafka | The replay commits the offset of a record it failed to republish | `DeadLetterReplayService.java:104-123` |
| SL-07 | MEDIUM | Kafka | Replay `send().get()` has no timeout and drops the `traceparent` header | `DeadLetterReplayService.java:105` |
| SL-09 | MEDIUM | Kafka | `BaseKafkaConsumer` javadoc says "no DLT"; the code throws to the DLT | `BaseKafkaConsumer.java:47-51` vs `:111-116` |
| SL-10 | MEDIUM | Kafka | `JsonKafkaDeserializer` throws `RuntimeException`, defeating the `ErrorHandlingDeserializer` DLT contract | `JsonKafkaDeserializer.java:38-42` |
| SL-11 | MEDIUM | Kafka | `JsonKafkaSerializer` silently writes `toString()` (non-JSON) on a serialization failure | `JsonKafkaSerializer.java:53-57` |
| SL-12 | MEDIUM | Kafka | The shared consumer-group default `shop-service` is a fleet-wide partition-competition trap | `KafkaProperties.java:226-228` |
| SL-13 | MEDIUM | Security | The `aud` validator accepts only the array form; RFC 7519 also allows a scalar | `BaseSecurityConfig.java:127-133` |
| SL-17 | MEDIUM | Security | `MethodArgumentTypeMismatchException` / `MultipartException` messages echoed to the client | `ApiExceptionHandler.java:104-115, 190-202` |
| SL-18 | MEDIUM | Security | `verifyCredentials` cannot distinguish "bad password" from "IdP down" | `KeycloakTokenClient.java:146-160` |
| SL-19 | MEDIUM | Security | Disabling a user can leave live sessions if the logout call fails | `KeycloakAdminClient.java:262-280` |
| SL-20 | MEDIUM | Security | Users are created with `emailVerified=true` and `temporary=false` hard-coded | `KeycloakAdminClient.java:570-593` |
| SL-22 | MEDIUM | Logging | Audit events are **discarded** on queue overflow; no metric, no alert | `BoundedAsyncAuditEventWriter.java:119-124` |
| SL-25 | MEDIUM | Security | A realm `ADMIN`/`MANAGER` role bypasses the client allow-list entirely | `ServiceCallerGuard.java:18, 38-40, 61-66` |
| SL-26 | MEDIUM | Security | `AuthenticatedUser.from(jwt)` takes authorities from the ambient context | `AuthenticatedUser.java:35-47, 86-89` |
| SL-29 | MEDIUM | Thread-safety | `download()` buffers up to 50 MB per concurrent call | `S3ObjectStorageService.java:110-124` |
| SL-30 | MEDIUM | Config | Five `shop.common.*` toggles are dead; the real gates are different properties | `CommonProperties.java:35-43` |
| SL-38 | MEDIUM | Config | `corsConfigurationSource` has no `@ConditionalOnMissingBean` ⇒ cannot be overridden | `CorsAutoConfigurer.java:31-33` |
| SL-39 | MEDIUM | Security | The CORS *code* default is `*`; only the shipped YAML narrows it | `SecurityProperties.java:113-117, 129` |
| SL-40 | MEDIUM | Security | The body-masking regex only understands JSON; form-encoded bodies are logged verbatim | `HttpLoggingFilter.java:123-132` |
| SL-41 | MEDIUM | Config | The shared `application.yml` activates the `dev` profile by default | `application.yml:20-22` |
| SL-21 | LOW | Thread-safety | `DateTimeUtils` cache eviction is a check-then-act whole-cache flush | `DateTimeUtils.java:31-33` |
| SL-23 | LOW | Security | `isAuthenticated()` is `true` for an anonymous token; the guard means something else | `AuditActorResolver.java:44-49` |
| SL-27 | LOW | Security | `expectedClientIds` has no null guard ⇒ latent NPE at startup | `SecurityProperties.java:120-131` |
| SL-31 | LOW | Thread-safety | `Messages` is a static global facade; last context wins in a multi-context JVM | `Messages.java:32, 38-40` |
| SL-32 | LOW | i18n | `ResourceBundle.getBundle` default-locale fallback disagrees with the Spring path | `Messages.java:63-73` |
| SL-42 | LOW | Kafka | `KafkaProperties.Retry` is dead configuration, yet the producer test is named "ExactlyOnce" | `KafkaProperties.java:109-127`; `KafkaPropertiesProducerDefaultsTest.java:31` |
| SL-43 | LOW | API | `JsonKafkaSerializer` is deprecated-for-removal but is still the default value serializer | `JsonKafkaSerializer.java:31`; `KafkaAutoConfiguration.java:53-56` |
| SL-44 | LOW | Docs | Three truncated/garbled comments indicate partially-reverted edits | `BaseSecurityConfig.java:136`; `JpaAuditingAutoConfiguration.java:59-61, 68` |
| SL-45 | LOW | Perf | `KeycloakAdminClient`/`TokenClient` use a 7–10-argument constructor instead of a builder | `KeycloakAdminClient.java:92-112` |

### 8.2 Detailed defect cards

#### SL-01 — HIGH — `synchronized (this)` on a shared bean, held across a network call
- **Evidence:** `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:402-449` — `synchronized (this) { ... restClient.post()...retrieve().body(...) }`.
- **Actual behaviour:** every admin operation (`createUser`, `deleteUser`, `assignRealmRole`, `isDirectAccessGrantEnabled`, …) calls `getAdminAccessToken()`. On a cache miss the thread takes the bean monitor and performs a blocking Keycloak token request **inside** it. All other admin calls in the JVM block on that monitor for the full round-trip.
- **Violated invariant:** *a shared bean's lock must not be held across a blocking remote call.* The `AtomicReference` double-checked locking (`:398, :403-406, :409-412`) is itself correct — the lock is only needed to prevent a token stampede, not to guard state.
- **Root cause:** the lock was introduced to prevent N concurrent token fetches; the fetch was then moved inside the critical section.
- **Proposed fix:** keep the double-checked locking but move the HTTP call **outside** the monitor — e.g. use a `CompletableFuture`/`ReentrantLock` with `tryLock` so only one thread fetches and the others wait on the future, or use Spring's `ConcurrentReferenceHashMap`/`@Cacheable` with `sync=true`. Add a connect+read timeout to the `RestClient` (see SL-33).
- **Test that would prove the fix:** a test that starts 20 threads calling `assignRealmRole` against a mock Keycloak that delays the token response by 500 ms, and asserts that the **number of token requests is 1** and that the total wall time is ≈ 500 ms (not 20 × 500 ms). Today the second assertion fails because the fetch is serialised inside the lock.

#### SL-02 — HIGH — silent downgrade from the service-account grant to the master-realm password grant
- **Evidence:** `KeycloakAdminClient.java:118-121` (`hasServiceAccountCredentials` requires secret **and** endpoint) and `:414-422` (the fallback to `GRANT_TYPE=password` with `adminUsername`/`adminPassword`).
- **Actual behaviour:** configure `shop.keycloak.admin-client-secret` but not `admin-client-secret` + a custom `serviceAccountTokenEndpoint` (i.e. half-configure the strong path) and the client silently issues a **master-realm password grant** on every admin call — the precise credential exposure F-078 was written to eliminate.
- **Violated invariant:** *the credential posture of the admin path must be explicit and fail-closed; a partial configuration must never downgrade to a weaker grant.*
- **Root cause:** the two grants are selected by an `if/else` on a *compound* predicate, with no "partially configured ⇒ error" branch.
- **Proposed fix:** make the selection explicit and fail fast — if `adminClientSecret` is set, the service-account path is **mandatory** and a missing endpoint is a startup error; if neither is set, the password grant requires both username **and** password (the `hasAdminCredentials()` predicate at `:172-175` already exists but is unused in the selection logic).
- **Test that would prove the fix:** a test constructing the client with a secret and a `null` service-account endpoint, asserting a `KeycloakClientException` (not a password-grant request). Today the client would attempt the master-realm password grant. `KeycloakAdminClientServiceAccountTest.java:139-155` already pins the fully-configured case; the half-configured case is the missing test.

#### SL-05 — HIGH — DLT replay is not resumable and re-publishes already-replayed records
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayService.java:73` — `props.put(ConsumerConfig.GROUP_ID_CONFIG, groupIdPrefix + "-dlq-replay-" + System.nanoTime());` with `:74` `AUTO_OFFSET_RESET_CONFIG = "earliest"`.
- **Actual behaviour:** every replay invocation creates a **brand-new consumer group** and starts reading the dead-letter topic from the **earliest** offset. The `commitSync()` calls at `:101, :118, :122` are discarded because the group id changes. A second operator-triggered replay therefore **re-publishes every record the first pass already replayed**, duplicating business events on the original topic. The `while (replayed + skipped + failed < maxRecords)` bound caps one pass, not the cumulative effect.
- **Violated invariant:** *a replay tool must be idempotent across invocations, or must make the operator's intent explicit (which records).*
- **Root cause:** the group id was made unique per invocation (to avoid a consumer-group rebalance blocking the service) without addressing that this also resets the read position.
- **Proposed fix:** use a **stable** group id (e.g. `shop-dlq-replay-<topic>`) so offsets persist across passes, and/or accept an explicit `fromOffset`/`sinceTimestamp` in `ReplayRequest`; additionally record replayed record ids in a small dedup store. Document the duplicate-publish behaviour explicitly if the tool is meant to be re-runnable.
- **Test that would prove the fix:** an integration test on an embedded broker that (1) puts one poison record in the DLT, (2) calls `replay(topic, 10, 3)` **twice**, and (3) asserts the original topic carries the payload **exactly once**. Today it carries it twice — the same duplication the existing test already *observes* but does not flag (`DeadLetterReplayServiceTest.java:109-111` asserts `isGreaterThanOrEqualTo(2)`).

#### SL-08 — HIGH — the Kafka consumer mapper rejects unknown properties, unlike every HTTP boundary
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:39-40` (`new ObjectMapper().registerModule(new JavaTimeModule())`) and `:102` (`WIRE_MAPPER.treeToValue(node, type)`); the same divergence in `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/Jackson2ObjectMapperAutoConfiguration.java:27-29`. Spring Boot's own mapper **disables** the feature — verified in the dependency source: `spring-boot-jackson-4.1.1-sources.jar` → `org/springframework/boot/jackson/autoconfigure/JacksonAutoConfiguration.java:518` → `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`.
- **Actual behaviour:** a producer that adds one field to a lifecycle-event DTO makes **every** record fail to bind → `PoisonRecordException` → 4 attempts → DLT, on **every** consumer of that topic, fleet-wide, with no compile-time or deploy-time signal. The HTTP boundary tolerates the same change.
- **Violated invariant:** *the wire contract of an event must evolve compatibly, at least as gracefully as the HTTP contract on the same fleet.*
- **Root cause:** the two mappers are constructed independently of one another; neither is derived from the Spring-managed mapper.
- **Proposed fix:** build the Kafka wire mapper from the **Spring-managed** `ObjectMapper` (copy its configuration, then override the deserialisation strictness), or at minimum call `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` and `.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)` on `WIRE_MAPPER`. Consider registering the DTOs' `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **Test that would prove the fix:** a test that binds a JSON payload carrying an **extra** field to a DTO and asserts the handler is invoked (no exception). Today it throws `PoisonRecordException` — the behaviour `BaseKafkaConsumerTypedBindTest.java:80-90` currently pins for a *type-incompatible* value (which must still fail).

#### SL-14 — HIGH — an unvalidated client header is written into the MDC and echoed back
- **Evidence:** `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/CorrelationIdFilter.java:39-43` (`MDC.put(MdcKey.CORRELATION_ID, correlationId)`), `:45-47` (copied into `MdcKey.TRACE_ID`), `:49-50` (`response.setHeader(...)`), `:61-64` (`headerOrNew` accepts the raw header value).
- **Actual behaviour:** the client fully controls the correlation id, the request id **and** the trace id that the fleet records. The value is rendered into **every** log line (`application.yml:106` → `logging.pattern.correlation: "[%X{traceId:-},%X{spanId:-}] "`) and is echoed into the response header. There is **no length cap** and **no charset whitelist**, so a client can (a) inflate log volume without bound and (b) choose the trace id that operators use to correlate an incident, defeating the trace's evidential value. `ApiResponse.traceId` (`common-core/.../ApiResponse.java:61-63`) and `AuditEvent.traceId` (`AuditAspect.java:65-66`) both carry the spoofed value.
- **Violated invariant:** *an identifier that is used for correlation must be either server-generated or validated against a strict format before it is trusted, stored, and echoed.*
- **Root cause:** the filter treats the header as an opaque correlation token and never validates it; there is no shared "correlation id policy" in the library.
- **Proposed fix:** validate the incoming value against a strict format (e.g. `^[A-Za-z0-9_-]{1,64}$`) and **regenerate a UUID** when it does not match; never copy a client value into `MdcKey.TRACE_ID` (leave trace ids to the tracing system); cap the echoed header length.
- **Test that would prove the fix:** a `MockMvc`/filter test that sends `X-Correlation-Id: <script>alert(1)</script>` and `X-Correlation-Id: ` + 10 000 `A`s, and asserts (a) the MDC value differs from the input and (b) the response header length is bounded.

#### SL-15 — HIGH — client IP resolved from spoofable headers, with a local-address bypass primitive
- **Evidence:** `utils/common-spring/src/main/java/com/shop/common/spring/web/util/RequestUtils.java:12-18` (the header list) and `:27-33` (first non-empty header wins); `:43-45` (`isLocalAddress`); the shared default `application.yml:47` (`server.forward-headers-strategy: framework`).
- **Actual behaviour:** `getClientIp` returns whatever the client put in `X-Forwarded-For` / `X-Real-IP` / …, with **no trusted-proxy allow-list** and no check that the direct peer is a known proxy. The value is logged for every request (`HttpLoggingFilter.java:81`) and is available to any service code. `isLocalAddress(ip)` then classifies `127.0.0.1`/`localhost` as local — a **ready-made bypass** for any future internal-only check. Today `isLocalAddress` is unused (verified by grep over the whole repository), so the bypass is latent rather than live; but the helper is public API of a shared library.
- **Violated invariant:** *a client-derived identity must only be trusted when the direct peer is a configured, trusted proxy that overwrites the header.*
- **Root cause:** the helper was written for a deployment behind a proxy that rewrites the headers, but the trust decision is not configurable and the library cannot know the topology.
- **Proposed fix:** add a `shop.web.trusted-proxies` property (CIDR list) and use `request.getRemoteAddr()` unless the peer is in the list; then parse the header chain right-to-left, skipping trusted hops. Expose `isLocalAddress` only alongside a trusted-peer check.
- **Test that would prove the fix:** a filter/unit test with `remoteAddr=203.0.113.9` and the header `X-Forwarded-For: 127.0.0.1` asserting that the resolved IP is **not** `127.0.0.1` when the peer is not trusted.

#### SL-16 — HIGH — unbounded per-request response buffering
- **Evidence:** `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java:64-66`
```java
ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, props.getRequest().getMaxBodyBytes());
ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
```
- **Actual behaviour:** the **request** wrapper is bounded (4096 bytes by default), but `ContentCachingResponseWrapper` has **no size limit** — it accumulates the entire response body in a `ByteArrayOutputStream` for the lifetime of the request, then `copyBodyToResponse()` writes it out (`:73`). The filter is registered by default (`WebAutoConfiguration.java:36-42`, `matchIfMissing = true`), so **every** response in **every** service is fully buffered in heap, regardless of whether body logging is enabled. A service serving media or large reports (the fleet has `media-service`) multiplies this by the concurrency level.
- **Violated invariant:** *a cross-cutting filter must not impose an unbounded per-request memory cost on every service that adopts it.*
- **Root cause:** the filter was written for small JSON APIs and the response wrapper's lack of a size limit was not accounted for.
- **Proposed fix:** bound the response buffering (a custom `HttpServletResponseWrapper` that stops caching past a threshold, or wrap only when `props.getResponse().isEnabled() && props.getResponse().isIncludeBody()`), and set `spring.servlet.multipart` limits alongside it.
- **Test that would prove the fix:** a `MockMvc`/embedded-container test that returns a 10 MB body and asserts the peak retained buffer size stays under the configured limit. Today the whole body is retained.

#### SL-24 — HIGH — `ServiceCallerGuard` fails open for an unauthenticated caller
- **Evidence:** `utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java:27-37`.
- **Actual behaviour:** `SecurityContextHolder.getContext().getAuthentication()` returns `null` for an unauthenticated request — so the guard's **normal** unauthenticated path is the `authentication == null` branch, which **returns normally** (allow). The guard therefore provides **no** protection unless the endpoint is separately protected. It is published by `SecurityAutoConfiguration.java:35-39` as a general-purpose "who may call this?" primitive, and its own javadoc (`:20-26`) describes it as asserting that the caller "is one of the allowed service clients".
- **Violated invariant:** *an authorization guard must deny when it cannot establish the caller's identity.* Fail-open is the default posture of every other check in this library (e.g. `BaseSecurityConfig.java:83` `anyRequest().authenticated()`, and the fail-fast credential checks in `ObjectStorageAutoConfiguration.java:50-61`).
- **Root cause:** the author treated "no authentication" as "the endpoint has its own `@PreAuthorize`" — true only when the endpoint is covered by the default filter chain, which the guard cannot verify.
- **Proposed fix:** invert the guard — `if (authentication == null || !authentication.isAuthenticated()) throw BusinessException.of(ErrorCode.UNAUTHORIZED, ...)`. If a lenient mode is wanted for anonymous endpoints, make it an explicit opt-in parameter (`requireCaller(CallerPolicy, String...)`).
- **Test that would prove the fix:** a unit test that clears the `SecurityContext` and asserts `requireCaller("order-service")` throws a `BusinessException` with `UNAUTHORIZED`. Today it returns normally.

#### SL-28 — HIGH — a network call inside a `@Bean` method, with a startup race
- **Evidence:** `utils/common-storage/src/main/java/com/shop/common/storage/config/ObjectStorageAutoConfiguration.java:63-72` (`service.ensureBucketExists(props.getBucket())` inside the `@Bean` method); `S3ClientFactory.java:30-37` (no `ClientOverrideConfiguration`, hence **no API-call timeout**); `S3ObjectStorageService.java:51-72` (`headBucket` → `createBucket`).
- **Actual behaviour:** (a) application startup now **depends on the object store being reachable**; with no client timeout configured, an unreachable store blocks the startup thread indefinitely instead of failing fast; (b) two replicas starting concurrently both observe a missing bucket and both call `createBucket`; the loser receives `BucketAlreadyOwnedByYou`/409, which `createBucket` (`:69-71`) converts into a `StorageException` → **startup failure** for that replica.
- **Violated invariant:** *bean creation must be side-effect free and deterministic; a transient dependency outage must not prevent the process from starting.*
- **Root cause:** an idempotent provisioning step was placed on the critical startup path, and the SDK client was built without a timeout or a retry policy.
- **Proposed fix:** move `ensureBucketExists` out of the `@Bean` method into an `ApplicationReadyEvent`/`SmartLifecycle` hook (or an explicit operator command), set a `ClientOverrideConfiguration` with `apiCallTimeout`/`apiCallAttemptTimeout` on the `S3Client`/`S3Presigner`, and make `createBucket` tolerate `BucketAlreadyOwnedByYou` (409) as success.
- **Test that would prove the fix:** (i) an `ApplicationContextRunner` test asserting the context **starts** with an unreachable endpoint; (ii) a test that pre-creates the bucket and asserts `ensureBucketExists` does **not** throw.

#### SL-33 — HIGH — Keycloak clients bypass the application-configured `RestClient.Builder`
- **Evidence:** `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java:62` (`traceAware(RestClient.builder())`) and `:89` (the same for the admin client). The class javadoc (`:29-41`) explains that the shared `traceparentRestClientBuilderPostProcessor` cannot reach these builders because they are "transient values consumed inside the bean construction, never exposed as `RestClient.Builder` beans".
- **Actual behaviour:** every application-level `RestClient` customisation is silently bypassed for the Keycloak clients — a custom `ClientHttpRequestFactory` (connection pooling, proxy, mTLS), Boot's `RestClientCustomizer` beans, Boot's observation instrumentation (so **no HTTP client metrics and no automatic tracing** for Keycloak calls), and the shared traceparent post-processor. The class also installs `SimpleClientHttpRequestFactory` (`:43-45`), which is **not pooled** — one TCP connect + TLS handshake per call — with a 5 s connect/read timeout.
- **Violated invariant:** *a shared library must not silently opt out of the platform's HTTP-client configuration; instrumentation and transport customisation must apply uniformly.*
- **Root cause:** the module cannot depend on `common-spring` (the cycle documented at `:29-33`), and the shared tracing seam lives in the dependent module.
- **Proposed fix:** inject the context's `RestClient.Builder` bean into `keycloakTokenClient`/`keycloakAdminClient` (Boot auto-configures one), and move the W3C propagation into a **leaf** module (or into `common-core`) so the post-processor can enrich it. If the cycle must persist, at least register the builders as beans so the post-processor can see them.
- **Test that would prove the fix:** a test that registers a `RestClientCustomizer` in the context and asserts that the `RestClient` used by `KeycloakTokenClient` carries the customisation (e.g. a header). Today it does not.

#### SL-34 — HIGH — the DLT-replay actuator endpoint has no authorization
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayEndpoint.java:10` (`@Endpoint(id = "deadLetterReplay")`) and `:26-40` (`@WriteOperation` with **no** `@PreAuthorize`/`@Secured`); `KafkaAutoConfiguration.java:88-94` (gated only by `shop.kafka.dlq-replay.enabled=true`).
- **Actual behaviour:** if an operator adds `deadLetterReplay` to `management.endpoints.web.exposure.include`, the endpoint becomes an unauthenticated `POST /actuator/deadLetterReplay` that re-publishes arbitrary dead-letter records to **any** topic named in the `kafka_dlt-original-topic` header (`DeadLetterReplayService.java:96-105`) — the topic is not validated against `KafkaTopicContract`. Combined with SL-05 (every pass re-reads from the beginning), the endpoint is a **mass-duplication primitive** against the fleet's event bus.
- **Violated invariant:** *an operator-triggered mutation of durable business state must require an authenticated, authorised principal.*
- **Root cause:** the endpoint was added as an actuator `@WriteOperation` without the security annotation that the rest of the platform relies on (`@EnableMethodSecurity` is enabled by `SecurityAutoConfiguration.java:31` and used in 38 production files).
- **Proposed fix:** annotate the endpoint method with `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` (or an equivalent), validate `deadLetterTopic` against `KafkaTopicContract.require(...)`, and add an explicit `dryRun` mode that only reports what *would* be replayed.
- **Test that would prove the fix:** a `WebMvcTest`/`ApplicationContextRunner` test asserting that an unauthenticated `POST /actuator/deadLetterReplay` returns 401/403. Today the endpoint has no security metadata at all.

#### SL-35 — HIGH — a listener factory without a recoverer silently drops records
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:87-92`
```java
if (template != null) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));
} else {
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
}
```
- **Actual behaviour:** the two-argument constructor (`:73-75`) sets `kafkaTemplate = null`, so the factory gets a `DefaultErrorHandler` **without** a recoverer. Spring's default recovery is `LoggingRecoverer`, which **logs and skips** the record — the event is **lost**, not dead-lettered. Because `BaseKafkaListenerConfig` is the base every service extends, a service that forgets to pass a template silently opts into data loss.
- **Violated invariant:** *a poison record must be retained for inspection; it must never be dropped on the floor after a retry budget is exhausted.*
- **Root cause:** the `template == null` branch was provided for convenience instead of failing fast.
- **Proposed fix:** make the template **mandatory** (single constructor), or make the no-recoverer path explicit via a `DeadLetterPolicy` enum (`REJECT_AND_STOP` / `LOG_AND_SKIP`) with `LOG_AND_SKIP` opt-in and a loud warning at bean creation.
- **Test that would prove the fix:** an embedded-broker test with the two-argument constructor that publishes a poison record and asserts that a record **exists** in `<topic>.DLT` (or that the container stops). Today it is logged and dropped.

#### SL-36 — HIGH — the shared consumer configuration pins none of the commit/isolation semantics
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:98-106`.
- **Actual behaviour:** `enable.auto.commit`, `isolation.level`, `max.poll.records`, `max.poll.interval.ms` and `session.timeout.ms` are left to the framework's implicit defaults. Commit semantics on a financial event bus are a decision that must be explicit, and `isolation.level` is the prerequisite for `read_committed` if transactions are ever introduced.
- **Violated invariant:** *offset-commit semantics of a shared consumer must be an explicit, reviewable configuration, not an inherited default.*
- **Root cause:** the map was assembled from the minimum needed to boot, and the retry/DLT block was separately declared "INTENTIONALLY UNUSED" (`:109-127`).
- **Proposed fix:** pin `enable.auto.commit=false`, `isolation.level=read_committed`, `max.poll.records`, `max.poll.interval.ms` and `session.timeout.ms` in `buildConsumerProperties()`, each configurable through `KafkaProperties.ConsumerConfig`, and assert them in `KafkaPropertiesProducerDefaultsTest` (renamed to cover both producer and consumer).
- **Test that would prove the fix:** a test asserting `props.buildConsumerProperties()` contains `enable.auto.commit=false` and `isolation.level=read_committed`.

#### SL-37 — HIGH — the fleet-wide `expected-client-ids` list is the platform's only horizontal-privilege boundary, and it is broad
- **Evidence:** `utils/common-spring/src/main/resources/application.yml:158`
```yaml
expected-client-ids: ${SHOP_SECURITY_EXPECTED_CLIENT_IDS:ecommerce-client,order-service,rating-service,search-service,product-service,media-service}
```
consumed by `BaseSecurityConfig.java:135-143`.
- **Actual behaviour:** every service that does not override the list accepts a token issued to **any** of those six clients. A token minted for `media-service` is accepted by `order-service`, and vice versa. The YAML comment (`:157`) says "Narrow it per service where the callers are known" — but the **shipped default is the broad list**, and no service in the fleet is required to narrow it.
- **Violated invariant:** *a service must only honour tokens issued to the clients that actually call it.*
- **Root cause:** the property was designed as an optional tightening mechanism (the aud/azp discussion at `:141-157`), but the default value was made convenient for the fleet, which inverts the fail-safe direction.
- **Proposed fix:** default the list to **empty** and require each service to declare its own callers, or invert the property into an *allow-list of callers per service* that is mandatory when `shop.security.enabled=true` and no explicit list is given. Failing startup for a service that declares no allowed clients is consistent with the fail-fast posture used for credentials.
- **Test that would prove the fix:** a test that asserts the shared `application.yml` does **not** carry a multi-client default, plus a per-service test asserting that a token with `azp=media-service` is rejected by a service that declares `azp=order-service`.

#### SL-03 — MEDIUM — `LazyJwtDecoder` builds more than one decoder under concurrent load
- **Evidence:** `LazyJwtDecoder.java:80-95` (`factory.get()` runs **before** the CAS at `:87`); the contradicted claim at `:32-33` ("a CAS on the delegate ensures only one real decoder is built even under concurrent first-request load").
- **Actual behaviour:** N concurrent first requests each perform a full OIDC discovery + JWKS fetch; only the winner's decoder is installed. A loser whose fetch fails throws `JwtException` (`:85`) → 401, even though the winner succeeded.
- **Violated invariant:** *a lazy singleton must construct its value exactly once, and a losing racer must not fail a request that a peer satisfied.*
- **Root cause:** the CAS was placed after the construction instead of guarding it; a `synchronized`/`Future` guard around the construction is what was intended.
- **Proposed fix:** build under a lock (or with a `CompletableFuture`) so exactly one construction happens, and have the losers read the winner's decoder.
- **Test that would prove the fix:** a test with a counting factory supplier and 50 concurrent `decode()` calls, asserting `factory.get()` was invoked **once**. Today it is invoked up to 50 times.

#### SL-04 — MEDIUM — a failed pre-warm turns every request into a synchronous IdP discovery
- **Evidence:** `LazyJwtDecoder.java:62-66` (the pre-warm thread) and `:80-95` (`buildOrFallback` runs `factory.get()` on the **request** thread whenever `delegate.get() == null`).
- **Actual behaviour:** if Keycloak is down at boot, the pre-warm fails (`:74-77`, logged at WARN) and **no negative cache** exists — every subsequent request thread performs a blocking discovery. Under load this exhausts the request thread pool and amplifies the IdP outage into a fleet-wide outage.
- **Violated invariant:** *a dependency outage must be contained to the dependency, not propagated into an unbounded retry storm.*
- **Root cause:** the fallback has no negative caching, no circuit breaker and no timeout.
- **Proposed fix:** add a negative-cache TTL (e.g. "do not retry the discovery for 30 s"), a bounded in-flight guard, and a `ClientHttpRequestFactory` with connect/read timeouts on the discovery client.
- **Test that would prove the fix:** a test whose factory throws, calling `decode()` 100 times, asserting the factory is invoked **once** (or at most once per TTL window).

#### SL-06 — MEDIUM — the replay commits the offset of a record it failed to republish
- **Evidence:** `DeadLetterReplayService.java:104-123` — the `commitSync()` at `:122` sits **outside** the `try`/`catch`, so it also runs for the failure path.
- **Actual behaviour:** a record whose republish failed has its offset committed, so it is not retried within the pass. The `maxConsecutiveFailures` guard stops the pass instead of retrying.
- **Violated invariant:** *an offset must only be committed for a record whose effect is durably achieved.*
- **Root cause:** the commit call was placed at loop scope rather than inside the success branch.
- **Proposed fix:** move `consumer.commitSync()` into the success branch and use `consumer.seek()` to rewind on failure, or track the last committed offset explicitly and commit only up to it.
- **Test that would prove the fix:** an embedded-broker test with a template that fails the first publish and succeeds the second, asserting the record is eventually republished in the same pass.

#### SL-07 — MEDIUM — the replay blocks without a timeout and loses the trace context
- **Evidence:** `DeadLetterReplayService.java:105` — `template.send(new ProducerRecord<>(originalTopic, record.key(), record.value())).get();` (no timeout; no `traceparent` header), versus `KafkaMessagePublisher.traceparentRecord()` (`KafkaMessagePublisher.java:239-246`) which injects the W3C header.
- **Actual behaviour:** a broker that accepts the connection but never acks blocks the actuator thread indefinitely; a replayed event carries **no** `traceparent`, so the downstream consumer starts a fresh root trace and the replay is invisible in the trace graph.
- **Violated invariant:** *a remote call must be time-bounded; a republished event must preserve its propagation context.*
- **Root cause:** the replay builds its own `ProducerRecord` instead of reusing the shared, trace-aware construction.
- **Proposed fix:** use `KafkaMessagePublisher`'s record construction (or inject the `KafkaMessagePublisher` instead of the raw `KafkaTemplate`) and bound the send with `future.get(timeout, unit)`.
- **Test that would prove the fix:** a test asserting the replayed record carries the same `traceparent` as the original, and that a non-acking broker causes a bounded `StorageException`/`KafkaPublishException` rather than an indefinite block.

#### SL-09 — MEDIUM — the `BaseKafkaConsumer` javadoc contradicts the code on DLT routing
- **Evidence:** `BaseKafkaConsumer.java:47-51` ("*ack-skips… no DLT, no container crash*") vs `:111-116` (throws `PoisonRecordException`).
- **Actual behaviour:** the **code is correct** (and is what the tests pin: `BaseKafkaConsumerTypedBindTest.java:70-78`, `BaseKafkaConsumerDeadLetterTest.java:84-94`). The defect is the stale contract comment, which will mislead an incident responder into believing there is no DLT and therefore no place to look for a lost event.
- **Violated invariant:** *a library's documented contract must match its behaviour.*
- **Root cause:** the class was changed from ack-skip to throw-and-DLT (F-001) and the class-level javadoc was not updated.
- **Proposed fix:** rewrite the javadoc to state the current behaviour (decode failure → `PoisonRecordException` → container retry (3×, 1 s) → DLT), and state explicitly that the DLT is only produced when a `KafkaOperations` template was supplied to the factory (SL-35).
- **Test that would prove the fix:** a documentation-assertion test is not idiomatic; the practical guard is the existing embedded-broker DLT test — extend it to assert the **retry count** (3 retries at 1 s) so a behaviour change cannot pass unnoticed.

#### SL-10 — MEDIUM — `JsonKafkaDeserializer` throws the wrong exception type for the DLT contract
- **Evidence:** `JsonKafkaDeserializer.java:38-42` (`throw new RuntimeException(...)`) vs its own javadoc at `:12-13` ("so that the `ErrorHandlingDeserializer` wrapper can route it to the DLT").
- **Actual behaviour:** Spring's `ErrorHandlingDeserializer` converts a delegate failure into a DLT-routable `RecordDeserializationException` only when the delegate throws **`SerializationException`**. A bare `RuntimeException` propagates as a *handler* failure: it is retried and then **recovered (skipped)** — the record is silently dropped instead of being dead-lettered.
- **Violated invariant:** *the exception type a deserializer raises is part of its contract with the container's error handler.*
- **Root cause:** the author chose `RuntimeException` without checking `ErrorHandlingDeserializer`'s catch clause.
- **Proposed fix:** throw `org.springframework.kafka.support.serializer.SerializationException` (or `DeserializationException`), and add a `configure()` implementation that honours `spring.json.trusted.packages` if the class is ever used with a polymorphic target type.
- **Test that would prove the fix:** a test that runs `JsonKafkaDeserializer` behind a real `ErrorHandlingDeserializer` and asserts the outcome is a `RecordDeserializationException` (→ DLT), not a bare `RuntimeException`.

#### SL-11 — MEDIUM — the serializer silently writes a non-JSON payload on failure
- **Evidence:** `JsonKafkaSerializer.java:53-57` (`catch (Exception ex) { return data.toString().getBytes(UTF_8); }`); it is the live default value serializer via `KafkaAutoConfiguration.java:53-56`.
- **Actual behaviour:** a Jackson failure (self-referencing DTO, unmapped type) publishes `data.toString()` — not JSON. The consumer's `WIRE_MAPPER.readTree` then fails → poison record → DLT, and the original data is gone. The failure is completely silent on the producer side.
- **Violated invariant:** *a serialiser must not publish a payload in a format the platform's own consumers cannot read; a serialisation failure must be visible at the source.*
- **Root cause:** a "never block the caller" fallback was implemented without a shape guarantee or a log line.
- **Proposed fix:** throw a `SerializationException` (Kafka's contract) and let the producer's error handler decide, **or** log at ERROR and publish a well-formed envelope carrying the original as a string. At minimum, log the failure.
- **Test that would prove the fix:** a test that serialises an object Jackson cannot handle and asserts the outcome is an exception (or a logged, parseable envelope) — never a raw `toString()`.

#### SL-12 — MEDIUM — the shared consumer-group default is a fleet-wide trap
- **Evidence:** `KafkaProperties.java:226-228` (`private String groupId = "shop-service";`), applied at `:102` (`props.put("group.id", consumer.getGroupId())`).
- **Actual behaviour:** any `@KafkaListener` that omits `groupId` inherits `shop-service`. Two services sharing that id **compete for the partitions** of a shared topic and each receives only a subset — a silent, partial event loss that looks like "the producer is broken". The class documents the hazard at length (`BaseKafkaListenerConfig.java:48-59`) and a contract test enforces the pinning (`KafkaListenerGroupIdContractTest.java:36-62`), but the **default itself remains armed** — and the shared `application.yml` does not set `shop.kafka.consumer.group-id`.
- **Violated invariant:** *a consumer-group identity must be unique per logical consumer; a shared default must not silently create partition competition.*
- **Root cause:** the default was chosen for convenience rather than as a fail-safe placeholder.
- **Proposed fix:** remove the default entirely (make `groupId` a required property) so a listener without a pinned group **fails at startup**; keep the contract test as the second line of defence.
- **Test that would prove the fix:** a test asserting `new KafkaProperties().buildConsumerProperties()` does **not** contain a `group.id`, or that the context fails to start when a listener omits it.

#### SL-13 — MEDIUM — the `aud` validator rejects the scalar form that RFC 7519 permits
- **Evidence:** `BaseSecurityConfig.java:127-133` (`if (!(aud instanceof List<?> audiences) || audiences.isEmpty()) return false;`); the behaviour is pinned by `BaseSecurityConfigAudienceValidationTest.java:38-43`.
- **Actual behaviour:** a JWT with `"aud": "orders-service"` (a legal single-string audience per RFC 7519 §4.1.3) is **rejected** with a 401. Any issuer that emits a scalar audience — or any future Keycloak audience mapper that does — would be locked out entirely.
- **Violated invariant:** *the validator must accept every audience encoding the specification allows.*
- **Root cause:** the validator was written against the Keycloak 26 shape (always an array) rather than against the specification.
- **Proposed fix:** normalise the claim first — accept `String`, `List<String>`, or a collection of `Object` filtered to `String` — then intersect with the expected list.
- **Test that would prove the fix:** change `BaseSecurityConfigAudienceValidationTest.java:38-43` to assert that a **scalar** `aud` is **accepted**, and add a case for a mixed-type array.

#### SL-17 — MEDIUM — two exception messages are echoed to the client verbatim
- **Evidence:** `ApiExceptionHandler.java:104-115` (`handleMalformedRequest` → `exception.getMessage()`) and `:190-202` (`handleMultipart` → `exception.getMessage()`).
- **Actual behaviour:** `MethodArgumentTypeMismatchException.getMessage()` embeds the **rejected value** ("For input string: \"<attacker input>\""), and `MultipartException.getMessage()` embeds the part name and content type. Both are reflected into the JSON body. The same class deliberately refuses to do this elsewhere (`:117-131`, "it can contain the raw [body]") and the DB path is hardened and pinned by `ApiExceptionHandlerConstraintLeakTest.java:20-48`.
- **Violated invariant:** *error responses must be curated; the raw exception text is an internal artefact.*
- **Root cause:** two handlers were written before the "curate every message" rule was adopted and were not revisited.
- **Proposed fix:** replace `exception.getMessage()` with a canned i18n message in both handlers; keep the parameter name if it is needed for usability, but never the rejected value.
- **Test that would prove the fix:** a `@WebMvcTest` that triggers a type mismatch with the value `"><script>alert(1)</script>` and asserts the response body does **not** contain it.

#### SL-18 — MEDIUM — "invalid credentials" is indistinguishable from "IdP down"
- **Evidence:** `KeycloakTokenClient.java:146-160` (`catch (KeycloakClientException e) { return false; }`); the exception carries the upstream status (`KeycloakClientException.java:11, 23-25`).
- **Actual behaviour:** a 500/503/timeout from Keycloak is reported as `false` — "the user's password is wrong". Callers that count failed attempts will **lock out legitimate users** during an IdP outage, and support will chase the wrong incident.
- **Violated invariant:** *an availability failure of a dependency must not be reported as an authentication failure.*
- **Root cause:** the boolean return type cannot express the distinction; the status is available but discarded.
- **Proposed fix:** return a tri-state result (`SUCCESS` / `INVALID_CREDENTIALS` / `UNAVAILABLE`) or rethrow `KeycloakClientException` for any status other than 401/400.
- **Test that would prove the fix:** a `MockRestServiceServer` test that answers 503 and asserts the outcome is **not** "invalid credentials" (e.g. an exception is thrown or a distinct status is returned).

#### SL-19 — MEDIUM — disabling a user can leave live sessions behind
- **Evidence:** `KeycloakAdminClient.java:262-280` — the PUT (disable) and the POST (logout) share one `try`; the catch at `:275-279` reports "Update user state failed".
- **Actual behaviour:** if the logout fails after a successful disable, the caller sees a failure but the user **is disabled and still holds live sessions and refresh tokens** — exactly the window the class's own `logoutUser` javadoc (`:282-294`) explains how to close.
- **Violated invariant:** *a disable operation must be atomic with respect to session revocation, or the partial failure must be reported as such.*
- **Root cause:** two independent remote calls were grouped into one error handler.
- **Proposed fix:** perform the logout **first**, then the disable, and report the two failures separately; or wrap the pair in a compensating action (disable → logout, and on logout failure retry the logout with backoff).
- **Test that would prove the fix:** a `MockRestServiceServer` test that answers 200 for the PUT and 500 for the logout, asserting the exception message identifies the **session-revocation** failure specifically.

#### SL-20 — MEDIUM — user creation hard-codes `emailVerified=true` and `temporary=false`
- **Evidence:** `KeycloakAdminClient.java:570-593` (`payload.put("emailVerified", true)` at `:576`; `credential.put("temporary", false)` at `:581`).
- **Actual behaviour:** every user created through the shared client is created with a **pre-verified** e-mail address, bypassing the identity provider's e-mail-verification step; and the password is always non-temporary, contradicting `resetUserPassword(..., boolean temporary)` (`:371-395`) which exposes the flag.
- **Violated invariant:** *identity lifecycle rules (verification, forced rotation) must be an explicit, per-call decision, not a silent default.*
- **Root cause:** the two flags were set while filling the payload map and never revisited.
- **Proposed fix:** add `boolean emailVerified` and `boolean temporary` parameters to `createUser` (defaulting to `false` for verification), and align `createUser` with `resetUserPassword`'s semantics.
- **Test that would prove the fix:** a `MockRestServiceServer` test asserting the create request body contains `"emailVerified":false` when the caller passes `false`.

#### SL-22 — MEDIUM — the audit trail discards events under back-pressure with no alert
- **Evidence:** `BoundedAsyncAuditEventWriter.java:47-49` (pool/queue constants), `:119-124` (`onRejection` → `discarded.incrementAndGet()` + a WARN every 100th), `:39` (the class contract).
- **Actual behaviour:** when the 1000-slot queue is full, audit events are **dropped**. `discardedEvents()` is exposed but **no metric is exported and nothing alerts**; the only signal is a WARN every 100th discard. A burst of traffic therefore produces a **silently incomplete audit trail** — the classic failure mode of an audit subsystem. As a secondary effect of `ThreadPoolExecutor` semantics, the pool does not grow from 2 to 4 threads until the queue is already full, so "max 4" is effectively unreachable in normal operation.
- **Violated invariant:** *an audit trail must be complete, or the loss must be observable as a metric that pages someone.*
- **Root cause:**the "never block the request thread" requirement was given absolute priority over completeness, with a counter as the only compensation.
- **Proposed fix:** export `discardedEvents()` and `failedEvents()` as Micrometer counters (`audit.events.discarded`, `audit.events.failed`) and register a health indicator that reports DEGRADED when the discard rate is non-zero; consider a bounded **spill** to disk instead of a pure discard, and size the queue from the measured event rate.
- **Test that would prove the fix:** the existing overflow test (`BoundedAsyncAuditEventWriterTest.java:77-110`) already proves the discard; add a test asserting the counters are **registered with the MeterRegistry** and that the health indicator reports the degradation.

#### SL-25 — MEDIUM — a realm ADMIN/MANAGER role bypasses the client allow-list
- **Evidence:** `ServiceCallerGuard.java:18` (`HUMAN_ADMIN_ROLES = List.of("ADMIN", "MANAGER")`), `:38-40` (early return), `:61-66` (the authority check).
- **Actual behaviour:** any principal holding the realm role `ADMIN` or `MANAGER` may invoke **any** endpoint guarded by `requireCaller`, including internal service-to-service endpoints that were meant to be callable only by named clients. The authorities come from `realm_access.roles` via `JwtRolesConverter` (`JwtRolesConverter.java:36-46`), so the grant is as broad as the Keycloak realm role itself.
- **Violated invariant:** *the set of principals allowed to invoke an internal operation must be enumerated by that operation, not widened by a global role.*
- **Root cause:** a convenience escape hatch for human administrators was placed inside a service-caller guard.
- **Proposed fix:** make the admin bypass **opt-in per call site** (`requireCaller(allowAdmins, allowedClientIds)`) rather than implicit, and/or use a dedicated, narrowly-scoped role (e.g. `PLATFORM_OPERATOR`) instead of the generic `ADMIN`/`MANAGER` realm roles.
- **Test that would prove the fix:** a test with an `ADMIN`-authority principal calling a guard that lists only `order-service`, asserting the expected outcome per the new contract.

#### SL-26 — MEDIUM — `AuthenticatedUser.from(jwt)` mixes two principals
- **Evidence:** `AuthenticatedUser.java:35-47` (the `from` factory) and `:86-89` (`currentAuthorities()` reads `SecurityContextHolder`).
- **Actual behaviour:** the `authorities` component comes from the **ambient** security context, not from the `jwt` argument. Calling `AuthenticatedUser.from(someJwt)` while a *different* principal is authenticated produces a record whose claims come from `someJwt` and whose authorities come from the other principal — an authorisation-relevant object assembled from two identities.
- **Violated invariant:** *a snapshot built from an explicit source must derive all its components from that source.*
- **Root cause:** the `authorities` field was filled from the convenient ambient source rather than from the token.
- **Proposed fix:** derive the authorities from the token itself (re-run `JwtRolesConverter` on the `jwt`), or rename the method to `fromCurrentContext()` and add a separate `fromTokenOnly(jwt)` that leaves the authorities empty.
- **Test that would prove the fix:** a test that authenticates principal A, then calls `AuthenticatedUser.from(jwtOfB)` and asserts the authorities correspond to B (or are empty).

#### SL-29 — MEDIUM — `download()` buffers up to 50 MB per concurrent call
- **Evidence:** `S3ObjectStorageService.java:110` (`MAX_BUFFER_BYTES = 50 * 1024 * 1024`), `:118-124` (the length check and `readNBytes`).
- **Actual behaviour:** the guard itself is **correct** — the advertised `contentLength` short-circuits at `:118`, and the `readNBytes(MAX+1)` bound catches a missing/lying header at `:121-124`. The defect is the **ceiling**: 50 MB per concurrent download means N concurrent downloads hold N × 50 MB of heap.
- **Violated invariant:** *a shared storage helper must bound its per-request memory cost to something compatible with normal heap sizing.*
- **Root cause:** the limit was chosen as a generous "safety" value rather than as a function of the service's heap budget.
- **Proposed fix:** make the limit configurable (`shop.storage.max-download-bytes`) with a conservative default (e.g. 8–16 MB), and steer large objects to `openStream` (`:142-151`) or a pre-signed URL, which already exist.
- **Test that would prove the fix:** a test that mocks a `GetObjectResponse` with `contentLength = 60 MB` and asserts the call throws **before** allocating, and a test that a 100 MB object is rejected at the configured limit.

#### SL-30 — MEDIUM — five `shop.common.*` toggles are dead configuration
- **Evidence:** `CommonProperties.java:35-43` (the record components) and `:85-147` (the sub-records); the class javadoc at `:22-23` claims these let *"operators flip a single switch that all services honour"*. Verified by grep: the **only** reader of `CommonProperties` in the entire repository is `CommonLibraryStarterTests.java:73-76`.
- **Actual behaviour:** `shop.common.security.enabled`, `shop.common.keycloak.enabled`, `shop.common.kafka.enabled`, `shop.common.storage.enabled` and `shop.common.logging.correlation-id` have **no effect**. The real gates are different properties (`shop.security.enabled`, `shop.keycloak.admin-*`, `shop.kafka.enabled`, `shop.storage.enabled`, `shop.web.correlation.enabled`). The worst case is `shop.common.kafka.enabled: false` in the shared `application.yml` (`:216`) while `shop.kafka.enabled` defaults to **true** (`KafkaProperties.java:25`, `KafkaAutoConfiguration.java:45` `matchIfMissing=true`) — an operator reading the shared config would believe Kafka is off when it is on.
- **Violated invariant:** *a documented platform switch must either control something or not be documented as a switch.*
- **Root cause:** the aggregator record was designed as a convenience façade and was never wired to the real conditionals.
- **Proposed fix:** either wire each toggle to its real condition (`@ConditionalOnProperty("shop.common.kafka.enabled")` etc.) **or** delete the dead components and the javadoc claim. Given the `common-spring` module already owns the per-module configs, deletion is the smaller change.
- **Test that would prove the fix:** an `ApplicationContextRunner` test per toggle asserting that flipping it changes the context (e.g. `shop.common.kafka.enabled=false` ⇒ no `KafkaTemplate` bean). Today nothing changes.

#### SL-38 — MEDIUM — the CORS source bean cannot be overridden
- **Evidence:** `CorsAutoConfigurer.java:31-33` — `@Bean public CorsConfigurationSource corsConfigurationSource(...)` with **no** `@ConditionalOnMissingBean`, unlike every other bean in the shared libraries.
- **Actual behaviour:** a service that wants its own `CorsConfigurationSource` (a different origin list, a per-tenant policy) must **replace** the bean definition. Spring Boot disables bean-definition overriding by default (`spring.main.allow-bean-definition-overriding=false`), so the context **fails to start** with `BeanDefinitionOverrideException` — the service has no supported way to customise CORS.
- **Violated invariant:** *the shared library's auto-configured beans must all be overridable.*
- **Root cause:** the `@ConditionalOnMissingBean` was omitted on this one bean.
- **Proposed fix:** add `@ConditionalOnMissingBean(CorsConfigurationSource.class)`.
- **Test that would prove the fix:** an `ApplicationContextRunner` test that registers a user `CorsConfigurationSource` and asserts the context starts and the user's bean wins.

#### SL-39 — MEDIUM — the CORS code default is a wildcard origin
- **Evidence:** `SecurityProperties.java:113-117` (`resolvedAllowedOriginPatterns()` → `List.of("*")` when unset) and `:129` (the compact constructor's fallback `new Cors(true, List.of("*"), ...)`); `CorsAutoConfigurer.java:42-45` (a **warning**, not a failure, when `allowCredentials=true` coexists with `*`).
- **Actual behaviour:** the shipped `application.yml` narrows this to an explicit localhost list (`application.yml:165-169`, pinned by `SharedDefaultsSecurityTest.java:47-62`), so the fleet default is safe. But the **library** default is `*`, and any service that constructs `SecurityProperties` directly, or that overrides `shop.security.cors.allowed-origin-patterns` with an empty list, silently gets "any origin". With `allow-credentials=true` this is the classic credentialed-wildcard misconfiguration, which the code only **warns** about.
- **Violated invariant:** *a permissive default must be opt-in, not the fallback when configuration is absent.*
- **Root cause:** the "empty ⇒ wildcard" convenience was kept for convenience rather than replaced with an explicit deny.
- **Proposed fix:** default the resolved origins to an **empty** list (deny all) and log an explicit warning when the effective policy is "any origin", or make `allow-credentials=true` with a wildcard a **startup failure** rather than a warning.
- **Test that would prove the fix:** a test constructing `SecurityProperties` with empty origins and asserting `resolvedAllowedOriginPatterns()` is empty (not `["*"]`), plus a test that `allowCredentials=true` + wildcard fails fast.

#### SL-40 — MEDIUM — the body masker only understands JSON
- **Evidence:** `HttpLoggingFilter.java:123-132` (`SENSITIVE_JSON_PATTERN` + `maskBody`).
- **Actual behaviour:** the masker matches JSON key/value pairs only. A **form-encoded** body (`username=admin&password=hunter2`) or an **XML** body is logged **verbatim** if body logging is enabled. The key list (`password|secret|token|credential|authorization|cardNumber|cvv|accessToken`) also misses common field names (`pin`, `otp`, `refreshToken` is covered, `newPassword` is covered, `apiKey` is **not**).
- **Violated invariant:** *a redaction filter must not be format-specific when the payload format is not.*
- **Root cause:** the masker was written for the JSON APIs the platform serves; multipart and form endpoints were not considered.
- **Proposed fix:** add a form-encoded redaction pass (`key=value` pairs) and an XML/text pass, extend the key list (`apiKey`, `pin`, `otp`, `clientSecret`, `privateKey`), and default `include-body` to `false` **for non-JSON content types** regardless of configuration.
- **Test that would prove the fix:** a test that posts a form-encoded body containing `password=` and asserts the logged line does not contain the secret.

#### SL-41 — MEDIUM — the shared defaults activate the `dev` profile
- **Evidence:** `utils/common-spring/src/main/resources/application.yml:20-22`
```yaml
spring:
  profiles:
    active: dev
```
- **Actual behaviour:** every service that depends on `common-spring` and does not override `spring.profiles.active` starts with the **`dev`** profile active. If a `application-dev.yml` exists in any service (or is added later), it silently applies in production. The fleet has no `prod` default, so a deployment that forgets `SPRING_PROFILES_ACTIVE` runs the dev profile.
- **Violated invariant:** *a shared library must not select an environment profile on behalf of the application.*
- **Root cause:** the convenience default was left in the shared file.
- **Proposed fix:** remove `spring.profiles.active` from the shared `application.yml` entirely (a library must not choose the application's profile), or default it to an empty/absent value so `spring.profiles.active` is only ever set by the deployment.
- **Test that would prove the fix:** a test that loads the shared `application.yml` and asserts `spring.profiles.active` is absent — mirroring the style of `SharedDefaultsSecurityTest.java` and `ServerErrorDefaultsTest.java`, which already assert the other shared defaults.

#### SL-21 — LOW — `DateTimeUtils` cache eviction is a check-then-act flush
- **Evidence:** `DateTimeUtils.java:31-33`.
- **Actual behaviour:** `containsKey` / `size` / `clear` is not atomic. Two threads can both observe the overflow and both flush the cache, discarding formatters other threads are about to reuse. The `ConcurrentHashMap` stays structurally sound and `DateTimeFormatter` is immutable, so the impact is a performance regression, not a correctness failure.
- **Violated invariant:** *cache eviction should not race with concurrent population.*
- **Root cause:** a size check plus a wholesale `clear()` was used instead of a bounded, eviction-aware map.
- **Proposed fix:** replace the map with a bounded cache (`ConcurrentHashMap` + an eviction on insertion under a small lock, or a library cache) and remove the pattern string from untrusted input paths.
- **Test that would prove the fix:** a concurrency test that hammers `format()` with 200 distinct patterns from 16 threads and asserts that no exception occurs and that the cache size stays bounded.

#### SL-23 — LOW — `isAuthenticated()` is `true` for an anonymous token
- **Evidence:** `AuditActorResolver.java:44-49`.
- **Actual behaviour:** Spring Security's `AnonymousAuthenticationToken.isAuthenticated()` returns `true`, so the `!authentication.isAuthenticated()` guard does **not** exclude anonymous callers; they are excluded only by the `instanceof Jwt` clause. The outcome is correct today (anonymous ⇒ `ANONYMOUS`), but the guard means something other than what it says.
- **Violated invariant:** *a guard must express the property it claims to test.*
- **Root cause:** the anonymous-token case was not considered when the guard was written.
- **Proposed fix:** add an explicit `authentication instanceof AnonymousAuthenticationToken` check (or `SecurityContextHolder` + `TrustResolver`) so the intent is unambiguous.
- **Test that would prove the fix:** a test that sets an `AnonymousAuthenticationToken` and asserts the actor is `ANONYMOUS` (the behaviour is already correct; the test pins the *reason*).

#### SL-27 — LOW — `expectedClientIds` has no null guard
- **Evidence:** `SecurityProperties.java:120-131` (the compact constructor guards `publicPaths`, `expectedAudiences` and `cors` but **not** `expectedClientIds`); the dereference is at `BaseSecurityConfig.java:135`.
- **Actual behaviour:** a null binding produces an NPE during **bean creation** rather than a validated startup failure. `@DefaultValue` makes the null case unlikely in practice, which is precisely what makes the asymmetry a trap.
- **Violated invariant:** *all collection-valued configuration must be null-normalised before use.*
- **Root cause:** the compact constructor was extended one field at a time.
- **Proposed fix:** add `if (expectedClientIds == null) expectedClientIds = List.of();` (or normalise all four in one loop).
- **Test that would prove the fix:** a test constructing `SecurityProperties` with a null `expectedClientIds` and asserting `expectedClientIds()` is empty and that `buildValidatorChain()` does not throw.

#### SL-31 — LOW — `Messages` is a static global facade
- **Evidence:** `Messages.java:32, 38-40`.
- **Actual behaviour:** a `static volatile` field with a public setter, written once by `I18nAutoConfiguration.i18nStaticBridge()` (`:62-65`). In a JVM hosting two Spring contexts (a test suite, or a container hosting two apps), the last initialised context wins **globally**, so one application's messages can answer another's exception messages.
- **Violated invariant:** *global mutable state must not be shared between independent application instances in the same JVM.*
- **Root cause:** the static facade was chosen to make the exception constructors usable outside Spring.
- **Proposed fix:** keep the static facade but make it a `ThreadLocal`-scoped or `InheritableThreadLocal`-scoped lookup, or resolve the `MessageSource` through a Spring-managed `Supplier` held in an `AtomicReference` that is **cleared** on context close.
- **Test that would prove the fix:** a test that starts two `ApplicationContext`s with different `MessageSource`s and asserts each context resolves its own message.

#### SL-32 — LOW — `ResourceBundle.getBundle` default-locale fallback disagrees with the Spring path
- **Evidence:** `Messages.java:63-73` (`ResourceBundle.getBundle(BUNDLE_BASENAME, locale)`) vs `:53-58` (the Spring path).
- **Actual behaviour:** `ResourceBundle.getBundle(String, Locale)` uses the default control, which falls back to `Locale.getDefault()` when the requested bundle is missing. A request for an unsupported locale can therefore be answered from the **JVM** default bundle rather than the configured `I18nProperties.defaultLocale` — and the class's own two resolution paths disagree. `ResourceBundle` also uses a JVM-global static cache.
- **Violated invariant:** *all resolution paths of a facade must implement the same policy.*
- **Root cause:** the fallback path was written as a convenience and does not honour the configured default locale or `fallbackToSystemLocale`.
- **Proposed fix:** use `ResourceBundle.Control.getNoFallbackControl(FORMAT_PROVIDER)` and fall back to `props.defaultLocale` explicitly, mirroring the Spring path.
- **Test that would prove the fix:** a test that requests an unsupported locale and asserts the resolved message comes from the configured default bundle, not the JVM default.

#### SL-42 — LOW — dead retry configuration, and a test name that overstates the guarantee
- **Evidence:** `KafkaProperties.java:109-127` ("Retry/DLT defaults — INTENTIONALLY UNUSED"); `KafkaPropertiesProducerDefaultsTest.java:31` (`buildProducerPropertiesKeepsAcksAllAndExactlyOnceByDefault`).
- **Actual behaviour:** two layers of mis-signal. (a) `shop.kafka.retry.*` is documented as "kept for the future" but reads as an active policy — an operator tuning it would see no effect. (b) The test name asserts "ExactlyOnce" for a configuration that delivers **idempotent production**, not exactly-once (§5.2).
- **Violated invariant:** *configuration and test names must describe what the system actually does.*
- **Root cause:** the retry block was retained for a future listener posture, and the test name was written from the intent rather than the effect.
- **Proposed fix:** either delete `KafkaProperties.Retry` or move it under an explicitly experimental namespace; rename the test to `buildProducerPropertiesKeepsAcksAllAndIdempotenceByDefault` and add a separate test asserting that **no** `transactional.id` is configured (i.e. exactly-once is deliberately absent).
- **Test that would prove the fix:** the renamed test plus an assertion that `buildProducerProperties()` does **not** contain `transactional.id`.

#### SL-43 — LOW — a deprecated-for-removal serializer is still the default value serializer
- **Evidence:** `JsonKafkaSerializer.java:31` (`@Deprecated(since = "2026-09-02", forRemoval = true)`); `KafkaAutoConfiguration.java:51-57` wires it into the `kafkaTemplate` bean, which is still created.
- **Actual behaviour:** the "deprecated" path is live: any service that injects the generic `KafkaTemplate<String,Object>` gets the double-encoding behaviour the deprecation warns about (`KafkaAutoConfiguration.java:28-41` describes the double-encoding bug this class caused). The deprecation note says the class is "retained only for non-outbox callers" — a migration that was never completed.
- **Violated invariant:** *a deprecated-for-removal component must have a tracked migration, not be the default path.*
- **Root cause:** the migration was never finished; the removal date was not honoured.
- **Proposed fix:** finish the migration — remove the `kafkaTemplate`/`ProducerFactory<String,Object>` beans entirely (only `stringKafkaTemplate` is used by the fleet), or keep them with an explicit `@ConditionalOnProperty("shop.kafka.typed-template.enabled")` defaulting to `false`.
- **Test that would prove the fix:** an `ApplicationContextRunner` test asserting that no `JsonKafkaSerializer` is instantiated by default, and a grep-based architecture test asserting the deprecated class is not referenced from `main`.

#### SL-44 — LOW — truncated/garbled comments indicate partially-reverted edits
- **Evidence:** `BaseSecurityConfig.java:136` — *"// never be satisfied. The 'azp' claim - the client the token was"* (a fragment); `JpaAuditingAutoConfiguration.java:59-61` — *"// @EnableJpaAuditing MUST be on the outer @AutoConfiguration class - when / sometimes skips it (depends on bean creation order), and the auditing listener never registers."* (a fragment); `JpaAuditingAutoConfiguration.java:68` — *"// the lambda may run on a thread without a propagated SecurityContext, returning"* (a fragment).
- **Actual behaviour:** three comments describe a rationale but do not state it. The **code** is correct in all three places; the risk is that a future maintainer cannot reconstruct the reasoning (which is non-obvious — the `@EnableJpaAuditing` placement is load-bearing, per the test at `JpaAuditingAutoConfigurationTest.java:37-45`) and may "simplify" it away.
- **Violated invariant:** *a non-obvious constraint must be documented in a form a reader can act on.*
- **Root cause:** three separate edits truncated the comment text.
- **Proposed fix:** rewrite the three comments as complete sentences that state the constraint and the failure mode; keep the existing tests as the executable specification.
- **Test that would prove the fix:** none needed — but the existing `JpaAuditingAutoConfigurationTest.java:37-58` (which asserts `jpaAuditingHandler` is present when an `EntityManagerFactory` exists and absent when opted out) is the guard that must keep passing.

#### SL-45 — LOW — constructor-overload explosion instead of a builder
- **Evidence:** `KeycloakAdminClient.java:68-112` — three public constructors with 7, 9 and 10 parameters; `KeycloakTokenClient.java:33-43` — 5 parameters.
- **Actual behaviour:** the 10-argument constructor takes four adjacent `String`s (`adminTokenEndpoint`, `usersEndpoint`, `rolesEndpoint`, `adminClientId`) whose order is not self-evident from the signature — a wrong-order call compiles and fails at runtime against the wrong endpoint. The same class also carries a documented but previously-broken endpoint mix-up (F-078/F-079, `KeycloakProperties.java:100-111`), which this signature style invites.
- **Violated invariant:** *a constructor with several same-typed parameters should be replaced by a builder or a parameter object.*
- **Root cause:** incremental endpoint additions were appended to the constructor list rather than introducing a configuration object.
- **Proposed fix:** introduce a `KeycloakAdminClientSettings` record (or a builder) carrying the endpoints and credentials, so the 10-argument call sites become `new KeycloakAdminClient(builder, settings)`.
- **Test that would prove the fix:** a compilation-level test (a single construction site in the test sources) plus the existing `KeycloakAdminClientGrantPostureTest`/`RoleMappingTest`/`ServiceAccountTest`, which already assert the exact URLs each call targets — those are the tests that would catch a wrong-order regression.

---

## 9. What is right (explicitly verified)

A production audit that only lists defects is not useful for a decision. The following were checked and found **correct**, with evidence:

1. **No shipped credentials anywhere.** Keycloak admin credentials, the object-store access/secret key and the realm client secrets all default to empty/placeholder values, and the object-store path **fails the startup** rather than falling back. (`StorageProperties.java:47, 54`; `ObjectStorageAutoConfiguration.java:50-61`; `application.yml:199-201`; pinned by `StoragePropertiesCredentialsTest.java:21-55`, `SharedDefaultsSecurityTest.java:31-45`, `RealmImportCredentialContractTest.java:88-149`.)
2. **No stack traces or SQL text to the client.** (`application.yml:55-60`; `ApiExceptionHandler.java:117-131, 204-218`; pinned by `ServerErrorDefaultsTest.java:40-71` and `ApiExceptionHandlerConstraintLeakTest.java:20-71`.)
3. **The audit actor never carries PII.** Only `sub`/`clientId`/`azp` are read; e-mail, name, phone and username claims are deliberately ignored. (`AuditActorResolver.java:9-25, 50-62`; pinned by `AuditAspectTest.java:89-109`.)
4. **The audit JSON escaping is correct** — `"`, `\`, `\n`, `\r`, `\t` and all control characters < 0x20 are escaped. (`AuditEvent.java:58-81`; pinned by `AuditEventTest.java:46-55`.)
5. **The Kafka topic contract is machine-enforced in four directions** — no undeclared consumed topic, no declared-but-unconsumed `CONSUMED` topic, no unjustified fire-and-forget topic, no declared-but-unproduced topic. (`KafkaTopicContract.java:48-66, 85-133`; `KafkaTopicContractTest.java:57-149`.)
6. **The producer is idempotent** (`acks=all` + `enable.idempotence=true` + `max.in.flight=1` + `retries=3`) and the wire contract is single-encoded JSON. (`KafkaProperties.java:80-96`; `KafkaAutoConfiguration.java:65-77`; pinned by `KafkaPropertiesProducerDefaultsTest.java:12-54`.)
7. **The consumer boundary never lets poison bytes tombstone a partition** — the value deserializer is `StringDeserializer`, and typed binding happens in the application layer where it can be contained. (`BaseKafkaListenerConfig.java:19-40, 109-111`; pinned by `BaseKafkaListenerConfigWireTest.java:68-82`.)
8. **JWT validation never blocks startup** and the validator chain always includes issuer + timestamp checks. (`BaseSecurityConfig.java:113-120, 122-126`; pinned by `BaseSecurityConfigJwtDecoderLazyTest.java:12-62`.)
9. **The platform's public-path list is narrow** — no `/actuator/env`, `/actuator/configprops`, `/actuator/heapdump` or `/actuator/loggers`. (`SecurityProperties.java:145-156`.)
10. **`ModelMapper` is genuinely thread-safe** in the pinned version 3.2.6 — verified against the dependency sources, not assumed (`ConcurrentHashMap` in `TypeMapStore.java:35`, `synchronized (lock)` in `TypeMapStore.java:121`, `synchronized (mappings)` in `TypeMapImpl.java:91, 120, 169, 355, 362`).
11. **AWS SDK v2 `S3Client`/`S3Presigner` are safe as singletons**, and Spring closes them on shutdown via the inferred destroy method.
12. **The `BoundedAsyncAuditEventWriter` is thread-safe** — `AtomicLong` counters, a `synchronized` file sink, a correct CAS-based warn throttle, and `close()` called by Spring on context shutdown.
13. **`KafkaMessagePublisher.publishBatch` is race-free** — `CountDownLatch` + `AtomicReferenceArray` with the outcome written **before** the countdown, giving the required happens-before edge.
14. **JWKS caching and rotation are handled** by Nimbus's `RemoteJWKSet` (cache + refresh-on-unknown-`kid`). There is no missing-JWKS-refresh defect.
15. **No polymorphic JSON typing is enabled anywhere**, so there is **no** `spring.json.trusted.packages` gap — the absence is correct, not an omission. (`BaseKafkaConsumer.java:30-32` states this accurately.)

---

## 10. Prioritised remediation plan

| Order | Defects | Why first |
| --- | --- | --- |
| 1 | SL-08, SL-11, SL-10 | Kafka wire-contract correctness — a producer-side DTO change currently dead-letters events fleet-wide, and the serializer can emit non-JSON silently. |
| 2 | SL-24, SL-15, SL-14, SL-34, SL-37 | The authorisation and identity-trust surface of the shared libraries: the fail-open guard, the spoofable IP, the unvalidated correlation id, the unauthorised replay endpoint, and the fleet-wide client list. |
| 3 | SL-01, SL-28, SL-16, SL-33 | Availability: monitor-held-across-I/O, network-in-`@Bean`, unbounded response buffering, and the bypassed `RestClient` configuration. |
| 4 | SL-05, SL-06, SL-07, SL-35, SL-36 | Kafka operational correctness: replay duplication, the lost-record path, the missing consumer settings. |
| 5 | SL-29, SL-22 | Memory and audit-completeness budgets. |
| 6 | SL-30, SL-38, SL-39, SL-41 | Configuration hygiene: dead toggles, the un-overridable CORS bean, the wildcard default, the `dev` profile. |
| 7 | The remaining MEDIUM/LOW items | Behavioural corrections and documentation repairs. |

### Effort/impact notes

- **SL-08, SL-24, SL-28, SL-30, SL-38, SL-41** are each a **one-to-three-line** change plus a test. They should be done first regardless of the ordering above.
- **SL-05** needs a decision (stable group id vs. explicit offsets vs. a dedup store), not just a patch — it is a *semantics* question about what a replay is for.
- **SL-16** needs a custom response wrapper; **SL-15/SL-14** need a shared "trusted input" policy that the library should own.

---

## 11. Method, limitations, and audit trail

- **Every one of the 135 Java files and 10 POMs listed in §1 was opened and read to EOF.** The "lines read" column is the full range for each file. No file was sampled, skimmed, or skipped.
- **No README or `docs/` file was used as evidence.** Where a doc-like claim appears in this report it is quoted from a **source comment** and then checked against the code — and where the two disagree (SL-09, SL-30, `common-core/package-info.java`) that disagreement is itself a finding.
- **Cross-module claims were verified, not assumed.** Examples: the `FAIL_ON_UNKNOWN_PROPERTIES` divergence was confirmed in the Spring Boot 4.1.1 dependency **source** (`JacksonAutoConfiguration.java:518`); the `ModelMapper` thread-safety claim was confirmed in the ModelMapper 3.2.6 **source** (`TypeMapStore.java:35, 121`; `TypeMapImpl.java:91, 120, 169, 355, 362`); the dead `CommonProperties` toggles were confirmed by a repository-wide grep; the `common-patterns` removal was confirmed with `git log`/`git ls-files`.
- **Limitation — service code was out of scope.** The task limited the audit to `utils/*`. Several findings state a *precondition* for exploitability that lives in a service (for example SL-35's "does the service pass a template?", SL-37's "does the service narrow the client list?"). Those preconditions are explicitly marked. A follow-up pass over the 14 service modules would confirm or retire them.
- **Limitation — no build was executed.** The audit is a static source review; the Maven quality gate (`./mvnw -T1C validate`) and the module test suites were **not** run, so no runtime behaviour was observed. Every "test that would prove the fix" in §8 is a *proposed* test.
- **No application source file was modified.** The only file written by this audit is this report.

### 11.1 Integrity note — concurrent working-tree changes

During this review the **shared working tree was modified by another agent** (a repo-wide comment-stripping pass applying the no-comment policy). The audit was therefore re-verified against the **pinned revision** rather than the live working tree:

- All 135 in-scope `.java` files were compared **code-line-for-code-line** (comments and whitespace normalised out) against `HEAD:a2b6e6f`: **every file is code-identical to the pinned revision.** The only working-tree deltas under `utils/` are removed Javadoc/comment blocks.
- Every line count and line range in the §1 inventory was re-verified against `git show HEAD:<path>`: **all 135 rows match exactly.**
- Consequently **every `file:line` citation in this report is valid against revision `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`**, which is the revision the audit was commissioned against. If the comments are later restored, the cited line numbers remain correct for the pinned revision; against the comment-stripped tree they will shift by the number of removed comment lines above the citation.
- The one apparent code difference found by the automated comparison (`CorsAutoConfigurer.java`) is a **false positive of the comparison script**: the literal `"/**"` in `source.registerCorsConfiguration("/**", …)` was mistaken for the start of a block comment. The file is code-identical to `HEAD` (verified by `git diff HEAD`, which shows only two comment blocks removed).
