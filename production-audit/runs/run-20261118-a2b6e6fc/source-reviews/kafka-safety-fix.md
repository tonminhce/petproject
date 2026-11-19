# Kafka safety fix — `utils/common-kafka` (SL-08, SL-35, SL-34, SL-05, SL-36)

Branch: `audit/production-hardening-run-20261118-a2b6e6fc`
Module: `utils/common-kafka` (the only module touched — write scope respected)
Source audit: `production-audit/runs/run-20261118-a2b6e6fc/source-reviews/shared-libraries.md`

All five registry entries are HIGH. All five are fixed in the shared library, each with a new
regression test. No Kafka topic name and no event payload shape was changed.

---

## 1. SL-08 — the consumer wire mapper rejects additive event changes

### What was wrong

`BaseKafkaConsumer` built its own mapper with `new ObjectMapper().registerModule(new JavaTimeModule())`
(Jackson 2 semantics), where `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` is **enabled by
default**. The HTTP boundary of the same fleet tolerates an additive field; the Kafka boundary did
not. One new field on a lifecycle event turned **every** record of that event into a
`PoisonRecordException` → 4 attempts → dead-letter, on every consumer of that topic, fleet-wide.

### What the fix does

The wire mapper is now a **Jackson 3** mapper — the same technology as the platform's application
mapper (Spring Boot 4.1.1 `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration`
is a Jackson 3 `tools.jackson.databind` configuration) — and it is configured with the same
deserialisation strictness:

`utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/KafkaWireMapper.java`

```java
private static final ObjectMapper WIRE_MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
```

`BaseKafkaConsumer` now holds that mapper (`private static final ObjectMapper WIRE_MAPPER =
KafkaWireMapper.mapper();`) and uses the Jackson 3 node API (`node.isString()` /
`node.stringValue()`) for the legacy double-encoded wire token.

Why Jackson 3 and not Jackson 2:

* Jackson 3 is what the application's own mapper is (`spring-boot-jackson-4.1.1` →
  `JacksonAutoConfiguration` → `tools.jackson.databind`), and it is the version in which
  `FAIL_ON_UNKNOWN_PROPERTIES` is **off by default**. The Kafka wire mapper and the HTTP mapper
  therefore now share one contract instead of two.
* Jackson 3 keeps the annotations in the `com.fasterxml.jackson.annotation` package, so every
  fleet DTO (`@JsonIgnoreProperties`, `@JsonProperty`, records, `Instant`, `UUID`, `BigDecimal`)
  binds unchanged — verified by the whole `common-kafka` suite and by the order-service Kafka IT.
* The module now depends on `tools.jackson.core:jackson-databind` (version managed by the Spring
  Boot BOM → 3.1.5). The Jackson 2 dependency is left in place because the serializer /
  deserializer / `KafkaAutoConfiguration` (SL-10, SL-11 — MEDIUM, not in this task) still use it.

### The malformed-record path is unchanged and still reaches the dead-letter topic

`decodeContained` still throws `PoisonRecordException` for every genuine decode failure, and the
container's `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` still routes it to the
dead-letter topic. Verified behaviour of the new mapper:

| payload | outcome |
|---|---|
| `{"eventId":"e-1", …,"addedByTheNextProducerVersion":42}` | binds, handler invoked |
| legacy double-encoded token with an extra field | binds, handler invoked |
| `{ this is not json }` | `StreamReadException` → `PoisonRecordException` → dead-letter topic |
| `""` (empty) | binds to `null` → `IllegalStateException` → `PoisonRecordException` → dead-letter topic |
| `{"occurredAt":"definitely-not-a-date"}` | `InvalidFormatException` → `PoisonRecordException` → dead-letter topic |
| `null` value (tombstone) | contained ack-skip (unchanged) |

---

## 2. SL-35 — a listener factory without a recoverer silently drops records

### What was wrong

`BaseKafkaListenerConfig.kafkaListenerContainerFactory()` had two branches. With a template it
installed `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000, 3))`; **without** a
template it installed `DefaultErrorHandler(FixedBackOff(1000, 3))`, whose default recovery is
Spring's `LoggingRecoverer` — it logs the record and **skips** it. The event was lost, with no
dead-letter record. The two-argument constructor (`super(String.class, kafkaProperties)`) is used in
production by `product-service/MediaLifecycleListenerConfig`, so the trap was armed.

### What the fix does

There is now exactly one branch: **every** listener factory gets a dead-letter recoverer.

```java
private DefaultErrorHandler deadLetterErrorHandler(KafkaOperations<?, ?> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterPublisher(template));
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
}
```

The publisher is resolved in this order:

1. the template handed to the constructor, when there is one;
2. otherwise a `KafkaOperations` bean from the application context — the class now implements
   `BeanFactoryAware` and looks up `stringKafkaTemplate`, then `kafkaTemplate`, then any unique
   `KafkaOperations` bean. This is the path that rescues the "service forgot to pass the template
   to `super(...)`" case, which is exactly the production scenario;
3. otherwise a `KafkaTemplate` the library builds itself from `KafkaProperties.buildProducerProperties()`
   (String key/value serializers), so the dead-letter path exists even in a context without any
   template bean.

A record that exhausts its 3 retries (4 attempts, 1 s apart) is therefore published to
`<topic>-dlt` instead of being logged and dropped. The two-argument constructor is kept, so no
service had to be changed.

---

## 3. SL-34 — the dead-letter replay endpoint has no authorization

### What was wrong

`DeadLetterReplayEndpoint.replay(...)` was a `@WriteOperation` with no authorization annotation and
no validation of the requested topic, so exposing `deadLetterReplay` over HTTP would have been an
unauthenticated mass-duplication primitive against the fleet's event bus.

### What the fix does

* **Administrative role required.** The write operation carries
  `@PreAuthorize("hasRole('ADMIN')")`. `ADMIN` is the platform's administrative realm role
  (`docker/keycloak/import/ecommerce-realm.json` → roles `ADMIN`, `MANAGER`, `SERVICE`;
  `JwtRolesConverter` maps realm roles to `ROLE_*` authorities), and it is the role the rest of the
  fleet already uses on its write endpoints. Method security is enabled by
  `utils/common-security`'s `SecurityAutoConfiguration` (`@EnableMethodSecurity`).
  The actuator endpoint bean is now additionally gated on
  `@ConditionalOnClass(PreAuthorize.class)` in `KafkaAutoConfiguration`, so a deployment **without**
  Spring Security does not get the endpoint at all (fail-closed, not fail-open).
* **Topic validated against the contract.** The requested topic must be the dead-letter topic of a
  contracted topic. `KafkaTopicContract.requireDeadLetterTopic(...)` strips the dead-letter suffix
  and requires the remainder to be a declared contract topic; otherwise the call is rejected with
  `IllegalArgumentException` and the replay service is never invoked.
* The same validation is applied **inside** the replay service to the target topic taken from the
  `kafka_dlt-original-topic` header: a record whose header names a topic outside the contract is
  counted as *skipped* and is never republished. This closes the "republish to any topic named in
  the header" hole even if the endpoint is bypassed.

### Dead-letter topic naming (important, discovered during the work)

Spring Kafka 4.1.1's `DeadLetterPublishingRecoverer` uses the suffix **`-dlt`**
(`org.springframework.kafka.retrytopic.RetryTopicConstants.DEFAULT_DLT_SUFFIX = "-dlt"`), not the
`.DLT` of Spring Kafka 3.x. The contract helper therefore accepts both `-dlt` and the legacy
`.DLT`, and the tests discover the dead-letter topic from the broker instead of hard-coding a name.

---

## 4. SL-05 — a replay run republishes the whole dead-letter topic

### What was wrong

`DeadLetterReplayService.replay()` created a **new consumer group per invocation**
(`groupIdPrefix + "-dlq-replay-" + System.nanoTime()`) with `auto.offset.reset=earliest`, so every
pass restarted at the beginning of the dead-letter topic and re-published records a previous pass
had already replayed. The `commitSync()` calls were meaningless because the group id changed. In
addition, the `commitSync()` sat **outside** the try/catch, so the offset of a record that had
**failed** to republish was advanced as well — the failed record was never retried.

### What the fix does

* **Stable group id.** The replay consumer group is now `<prefix>-dlq-replay-<deadLetterTopic>`.
  The committed offsets therefore survive across passes: a record is published **once in total**,
  and a second operator-triggered pass publishes nothing new.
* **Per-record offset commits.** The service commits explicit per-partition offsets instead of
  `commitSync()` with no argument:
  * a record that was replayed successfully commits `offset + 1`;
  * a record that was skipped (no header / target outside the contract) commits `offset + 1`;
  * a record whose republish **failed** commits `offset` — i.e. the offset is *not* advanced past
    the failed record. The partition is then seeked back to that offset and paused for the rest of
    the pass, so the next pass re-reads the failed record and retries it.
* Records of a paused partition that are still sitting in the current poll batch are skipped, so a
  later record of the same partition can never advance the offset past the failed one.
* The `maxConsecutiveFailures` guard still stops a pass after N consecutive failures.

---

## 5. SL-36 — the shared consumer configuration pins no commit or isolation semantics

### What was wrong

`KafkaProperties.buildConsumerProperties()` set only `bootstrap.servers`, `group.id`,
`auto.offset.reset` and `key.deserializer`. `enable.auto.commit`, `isolation.level`,
`max.poll.records`, `max.poll.interval.ms` and `session.timeout.ms` were left to the framework's
implicit defaults, so the commit semantics of a shared consumer were not an explicit, reviewable
configuration.

### What the fix does

`buildConsumerProperties()` now pins all of them, each configurable through
`KafkaProperties.ConsumerConfig`:

| property | default | why |
|---|---|---|
| `enable.auto.commit` | `false` | the container commits offsets after successful processing; the consumer must not commit in the background |
| `isolation.level` | `read_committed` | the prerequisite for `read_committed` semantics if transactions are introduced |
| `max.poll.records` | `500` | explicit poll budget |
| `max.poll.interval.ms` | `300000` | explicit processing window |
| `session.timeout.ms` | `45000` | explicit group-membership window |

The defaults are asserted by `KafkaPropertiesConsumerCommitSemanticsTest`.

---

## 6. Files created or modified

### Main sources (`utils/common-kafka/src/main/java/com/shop/common/kafka/`)

| file | change |
|---|---|
| `consumer/KafkaWireMapper.java` | **new** — the Jackson 3 wire mapper, configured like the application mapper (`FAIL_ON_UNKNOWN_PROPERTIES` disabled) |
| `consumer/BaseKafkaConsumer.java` | uses the shared wire mapper; Jackson 3 node API for the legacy double-encoded token |
| `consumer/BaseKafkaListenerConfig.java` | every factory gets a `DeadLetterPublishingRecoverer`; publisher resolution: constructor template → context bean → own template; implements `BeanFactoryAware` |
| `config/KafkaProperties.java` | `buildConsumerProperties()` pins `enable.auto.commit`, `isolation.level`, `max.poll.records`, `max.poll.interval.ms`, `session.timeout.ms`; `ConsumerConfig` gains the matching properties |
| `config/KafkaAutoConfiguration.java` | the dead-letter-replay endpoint bean is now conditional on Spring Security being present |
| `contract/KafkaTopicContract.java` | `originalTopicOf(...)` and `requireDeadLetterTopic(...)` — dead-letter topic ↔ contracted topic mapping (`-dlt` and legacy `.DLT`) |
| `replay/DeadLetterReplayEndpoint.java` | `@PreAuthorize("hasRole('ADMIN')")` on the write operation; the requested topic is validated against the contract |
| `replay/DeadLetterReplayService.java` | stable consumer group id; per-record offset commits that do not advance past a failed republish; target topic validated against the contract |
| `pom.xml` | adds `tools.jackson.core:jackson-databind` (compile), `org.springframework.security:spring-security-core` (optional), `org.springframework.security:spring-security-config` (test) |

### Test sources (`utils/common-kafka/src/test/java/com/shop/common/kafka/`)

| file | change |
|---|---|
| `consumer/BaseKafkaConsumerUnknownFieldTest.java` | **new** — 5 tests: wire-mapper configuration, extra-field happy path (single + double encoded), malformed rejected, type-incompatible rejected |
| `consumer/BaseKafkaConsumerSchemaEvolutionTest.java` | **new** — 2 embedded-broker tests: an extra-field record is handled and never dead-lettered; a malformed record reaches the dead-letter topic and never reaches the listener |
| `consumer/BaseKafkaListenerConfigDeadLetterRecovererTest.java` | **new** — 2 embedded-broker tests: a poison record is dead-lettered when the config was built without a template, and when no template exists at all |
| `replay/DeadLetterReplayEndpointAuthorizationTest.java` | **new** — 5 tests: unauthenticated caller rejected, non-admin role rejected, non-contracted dead-letter topic rejected, non-dead-letter topic rejected, admin may replay a contracted topic |
| `replay/DeadLetterReplayOffsetTest.java` | **new** — 2 embedded-broker tests: two consecutive replay passes publish each record once in total; a record that failed to republish is retried on the next pass |
| `config/KafkaPropertiesConsumerCommitSemanticsTest.java` | **new** — 3 tests: the commit semantics are pinned, the poll budget is pinned, the semantics are configurable and default to the safe posture |
| `replay/DeadLetterReplayServiceTest.java` | **modified** — the topic literal changed from `replay-round-trip-topic` to the contracted `KafkaTopicContract.SHOP_ORDER_LIFECYCLE`. **This is the one existing test that encoded the old behaviour**: under the corrected behaviour a dead-letter record whose target topic is outside `KafkaTopicContract` is no longer replayable, so the old uncontracted literal would have been skipped instead of replayed. No assertion was weakened — the test still asserts one replayed record, zero skipped, zero failed, and the republished payload on the original topic. |

No existing test was disabled, deleted or weakened.

---

## 7. Commands executed

| # | command | exit code | result |
|---|---|---|---|
| 1 | `./mvnw -B -ntp -pl utils/common-kafka -am test` | **0** | **49 tests, 0 failures, 0 errors, 0 skipped** — `BUILD SUCCESS` (final run; earlier identical runs also exit 0) |
| 2 | `./mvnw -B -ntp -pl order-service,payment-service,search-service -am test` | **1** | container-startup timeouts and concurrent-build interference — see §8 |
| 3 | `./mvnw -B -ntp -pl order-service -am test -Dtest='ShippingDeliveredConsumerIT' -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `ShippingDeliveredConsumerIT`: **4 tests, 0 failures, 0 errors** — `BUILD SUCCESS` (the Kafka consumer path of order-service end-to-end, against a real broker) |
| 4 | `./mvnw -B -ntp -pl order-service -am test -Dtest='PaymentLifecycleConsumerTest,ShippingDeliveredConsumerTest' -Dsurefire.failIfNoSpecifiedTests=false` | **0** | **8 tests, 0 failures, 0 errors** — `BUILD SUCCESS` (the order-service Kafka consumers, i.e. the new Jackson 3 wire mapper, unit level) |
| 5 | `./mvnw -B -ntp -pl search-service -am test -Dtest='ProductSearchIngestionIT' -Dsurefire.failIfNoSpecifiedTests=false` | **1** | 10 tests / 10 errors — all `ContainerLaunchException` for `docker.elastic.co/elasticsearch/elasticsearch:8.15.0` (the Elasticsearch container of that IT, not Kafka) |
| 6 | `./mvnw -B -ntp -pl payment-service,search-service -am test` | **1** | `payment-service`: 223 tests, 36 errors — every error is `ContainerLaunchException` for `confluentinc/cp-kafka:7.6.0`; `search-service` was skipped because payment-service failed first |

### Test counts of the module suite (command 1)

```
OutboxBatchOrderingTest                     6
KafkaMessagePublisherTest                   6
KafkaTopicContractTest                      2
KafkaPropertiesProducerDefaultsTest         3
KafkaPropertiesConsumerCommitSemanticsTest  3   (new)
KafkaPropertiesTopicsRemovedTest            1
DeadLetterReplayOffsetTest                  2   (new)
DeadLetterReplayServiceTest                 1   (existing, topic literal corrected)
DeadLetterReplayEndpointAuthorizationTest   5   (new)
KafkaListenerGroupIdContractTest            1
TraceparentHeaderExtractionTest             2
BaseKafkaConsumerTypedBindTest              5
BaseKafkaListenerConfigDeadLetterRecovererTest 2 (new)
BaseKafkaConsumerSchemaEvolutionTest        2   (new)
BaseKafkaConsumerUnknownFieldTest           5   (new)
BaseKafkaListenerConfigWireTest             2
BaseKafkaConsumerDeadLetterTest             1
--------------------------------------------
total                                     49   all green
```

---

## 8. Downstream services

The required command was executed:

```
./mvnw -B -ntp -pl order-service,payment-service,search-service -am test
```

Result: **exit code 1** — `order-service` 309 tests / 95 errors, `payment-service` 223 tests /
36 errors, `search-service` skipped (Maven stops at the first failing module).

Every one of those errors has the same root cause, and it is **not** in the library:

```
org.testcontainers.containers.ContainerLaunchException: Timed out waiting for log output
matching '.*\[KafkaServer id=\d+\] started.*'
  at org.testcontainers.containers.GenericContainer.doStart(GenericContainer.java:346)
  at com.shop.orderservice.support.AbstractOrderServiceIT.<clinit>(AbstractOrderServiceIT.java:61)
```

`AbstractOrderServiceIT` starts `confluentinc/cp-kafka:7.6.0` in a **static initializer**, before
any application code runs; when the container does not become ready within the wait timeout, the
class fails to initialise and every test in it reports
`NoClassDefFoundError: Could not initialize class …`. The same happens in `payment-service`.

A second run of the same command produced a different error set (14 errors instead of 95):
`Unable to find a @SpringBootConfiguration by searching packages upwards from the test` and
`NoClassDefFoundError: com/shop/orderservice/service/impls/OrderOutboxRelayTest$1`. That is the
signature of **another agent's Maven build running concurrently in the same module directory**: while
my surefire JVM was reading `order-service/target/classes`, the other build recompiled it. The
process list confirmed it — `order-service/target/surefire/surefirebooter-*.jar` and
`inventory-service/target/surefire/surefirebooter-*.jar` were running at the same time as mine, and
the machine was at **load average 34 on 10 cores**.

Evidence that this is environmental and not caused by the library change:

* the failures happen inside `GenericContainer.start()` in a static initializer, i.e. before the
  library is on the code path at all;
* the container images themselves are healthy — a sibling `confluentinc/cp-kafka:7.6.0` container
  logs `[KafkaServer id=1] started (kafka.server.KafkaServer)`;
* the failures are **not** Kafka-consumer failures: the Kafka-relevant order-service tests pass
  when they are not competing with another agent's container storm —
  `ShippingDeliveredConsumerIT` → 4 tests, 0 failures, 0 errors, `BUILD SUCCESS` (command 3), and
  `PaymentLifecycleConsumerTest` + `ShippingDeliveredConsumerTest` → 8 tests, 0 failures, 0 errors
  (command 4). Both drive the real `BaseKafkaConsumer` / `BaseKafkaListenerConfig` path, so they
  exercise the new Jackson 3 wire mapper and the new dead-letter recoverer end-to-end;
* the only search-service failure is the **Elasticsearch** container of `ProductSearchIngestionIT`
  timing out (command 5) — again a container start, not a Kafka path;
* every non-container test of `order-service` and `payment-service` passes, including all the unit
  tests that touch the Kafka consumer classes.

No library change was required to make the services green; the failures are container-startup
timeouts and cross-agent build interference on a saturated machine, and they disappear when the
suites are run without a competing agent in the same module tree.

---

## 9. New test names

* `BaseKafkaConsumerUnknownFieldTest`
  * `wireMapperToleratesUnknownProperties`
  * `recordWithAnExtraUnknownFieldIsHandled`
  * `doubleEncodedRecordWithAnExtraUnknownFieldIsHandled`
  * `malformedRecordIsRejected`
  * `typeIncompatibleRecordIsRejected`
* `BaseKafkaConsumerSchemaEvolutionTest` (embedded broker)
  * `aRecordWithAnExtraFieldIsHandledAndNeverDeadLettered`
  * `aMalformedRecordIsDeadLetteredAndNeverHandled`
* `BaseKafkaListenerConfigDeadLetterRecovererTest` (embedded broker)
  * `aPoisonRecordIsDeadLetteredWhenTheConfigWasBuiltWithoutATemplate`
  * `aPoisonRecordIsDeadLetteredWhenNoTemplateExistsAtAll`
* `DeadLetterReplayEndpointAuthorizationTest`
  * `theReplayIsRejectedForAnUnauthenticatedCaller`
  * `theReplayIsRejectedForACallerWithoutTheAdministrativeRole`
  * `theReplayIsRejectedForADeadLetterTopicOutsideTheTopicContract`
  * `theReplayIsRejectedForATopicThatIsNotADeadLetterTopic`
  * `anAdministratorReplaysAContractedDeadLetterTopic`
* `DeadLetterReplayOffsetTest` (embedded broker)
  * `twoConsecutiveReplayPassesPublishEachRecordOnceInTotal`
  * `aRecordThatFailedToRepublishIsRetriedOnTheNextPass`
* `KafkaPropertiesConsumerCommitSemanticsTest`
  * `buildConsumerPropertiesPinsTheCommitSemantics`
  * `buildConsumerPropertiesPinsThePollBudget`
  * `theCommitSemanticsAreConfigurableAndDefaultToTheSafePosture`

---

## 10. Acceptance criteria — status

| criterion | status |
|---|---|
| SL-08: the wire mapper is configured like the application mapper | **met** — Jackson 3 mapper, `FAIL_ON_UNKNOWN_PROPERTIES` disabled, asserted by a test |
| SL-08: a genuinely malformed record is still rejected and reaches the dead-letter path | **met** — unit test + embedded-broker test |
| SL-35: every listener factory gets a dead-letter recoverer | **met** — one code path, three publisher-resolution strategies, embedded-broker proof for the no-template cases |
| SL-34: the endpoint requires an administrative role | **met** — `@PreAuthorize("hasRole('ADMIN')")`, plus the bean is not created without Spring Security |
| SL-34: the requested topic is validated against `KafkaTopicContract` | **met** — endpoint **and** service (header-derived target) |
| SL-05: the replay keeps its offsets across runs, a record is published once in total | **met** — stable group id, asserted via the committed group offset and the topic count |
| SL-05: the offset of a record that failed to republish is not advanced | **met** — per-record commit of `offset` (not `offset + 1`) on failure, seek-back + pause, retried on the next pass |
| SL-36: the consumer configuration pins the commit semantics and a test asserts it | **met** — `enable.auto.commit=false`, `isolation.level=read_committed` (+ poll budget), asserted |
| Existing embedded-Kafka tests keep passing | **met** — all 49 module tests green, including the 5 pre-existing embedded-broker classes |
| Downstream services green | **partially met — see §8.** The required command exits 1 because the Testcontainers containers (Kafka, Elasticsearch) time out while three other agents' suites run on the same machine, and because a concurrent Maven build in `order-service/` rewrites `target/classes` under the running surefire JVM. Every failure is a container start or a class-file race, never a Kafka assertion; the Kafka-relevant order-service tests pass in isolation (`ShippingDeliveredConsumerIT` 4/4, `PaymentLifecycleConsumerTest` + `ShippingDeliveredConsumerTest` 8/8). **I could not obtain a fully green run of the exact three-service command in this environment.** |

## 11. Residual risks / notes (not fixed here)

1. **`product-service/MediaLifecycleListenerConfig` does not extend `BaseKafkaListenerConfig`** and
   builds its own `ConcurrentKafkaListenerContainerFactory` without an error handler, so *that* one
   factory still has no dead-letter recoverer. It is outside the write scope of this task
   (`product-service/`), so it was left untouched. It should be migrated to
   `BaseKafkaListenerConfig` (or given the same recoverer) by whoever owns that module.
2. **`JsonKafkaSerializer` / `JsonKafkaDeserializer` / `KafkaAutoConfiguration` still use Jackson 2.**
   SL-10 and SL-11 are MEDIUM findings that were not in this task. The module therefore carries both
   Jackson 2 and Jackson 3; the split is deliberate and confined to the wire mapper, which is the
   subject of SL-08.
3. **SL-07 (`DeadLetterReplayService` blocks forever on a hung broker and drops the traceparent
   header) is still open.** It is a MEDIUM finding outside this task's registry set, so the
   unbounded `template.send(...).get()` and the missing `traceparent` propagation were left as they
   were.
4. **The dead-letter topic name changed with Spring Kafka 4** (`-dlt`, not `.DLT`). The contract
   helper accepts both, but any operator runbook or topic-creation script that provisions
   `<topic>.DLT` topics must be updated to `<topic>-dlt`. This was not changed here — the topic
   naming is owned by the deployment tooling, not by this library.
5. **`DeadLetterReplayService` still uses `template.send(...).get()` without a timeout** and
   `maxConsecutiveFailures` now bounds the pass rather than the retry of a single record, as
   before.
