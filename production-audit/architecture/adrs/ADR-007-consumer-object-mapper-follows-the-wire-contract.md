# ADR-007: The Kafka wire mapper ignores unknown properties

- Status: Accepted
- Context: `BaseKafkaConsumer` used a bare `ObjectMapper`, so unknown-property
  rejection stayed enabled, while the application mapper disables it. Adding one field
  to a produced record would turn every consumer of that record into a poison record.
- Decision: The wire mapper is configured like the application mapper, so an additive
  change to an event contract does not break the consumers of that contract.
- Consequences: event contracts can evolve additively. A genuinely malformed record is
  still rejected by the deserializer and routed to the dead-letter topic.
- Evidence: `utils/common-kafka/.../consumer/BaseKafkaConsumer.java:39-40,102`.
