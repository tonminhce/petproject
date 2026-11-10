# Configuration & Configuration Drift

Generated: 2026-11-10T10:05:42

Findings in this category: **1**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-003 | P3 | OPEN | Dead legacy topic-name configuration in KafkaProperties.Topics |

---

### F-003 - Dead legacy topic-name configuration in KafkaProperties.Topics

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-kafka
- **Source files:**
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java`
- **Actual implementation:** shop.kafka.topics.* defaults to order-events / payment-events / notification-events / inventory-events. No service references these keys; the real topic names are shop.*.lifecycle.v1 string literals in the publishers.
- **Expected:** Configuration properties map to values actually consumed, or are removed.
- **Trigger:** An operator reading configuration to discover topic names.
- **Root cause:** Pre-v1 naming retained after the topic rename.
- **Consequences:** Misleading configuration; an operator could 'fix' a key that nothing reads.
- **Required remediation:** Remove the Topics block or bind it to the real topic names.
- **Regression test:** Config-binding test asserting shop.kafka.topics values equal the literals used by the publishers.
- **Remediation applied:** Not yet implemented - the dead shop.kafka.topics.* block is still present. Tracked as task TSK-003.
- **Verification evidence:** (none yet)
