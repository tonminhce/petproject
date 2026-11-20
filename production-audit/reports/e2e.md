# End-to-End Verification

Generated: 2026-11-19T22:33:06Z

Run identifier: `run-20261118-a2b6e6fc`

## Artefacts

- `petproject-comprehensive.postman_collection.auth.json`: state n/a
- `petproject-comprehensive.postman_collection.catalog.json`: state n/a
- `petproject-comprehensive.postman_collection.checkout.json`: state n/a
- `petproject-comprehensive.postman_collection.gateway.json`: state n/a
- `petproject-comprehensive.postman_collection.inventory.json`: state n/a
- `petproject-comprehensive.postman_collection.json`: state n/a
- `petproject-comprehensive.postman_collection.kafka.json`: state n/a
- `petproject-comprehensive.postman_collection.media.json`: state n/a
- `petproject-comprehensive.postman_collection.payment.json`: state n/a
- `petproject-comprehensive.postman_collection.ratings.json`: state n/a
- `petproject-comprehensive.postman_collection.refund.json`: state n/a
- `petproject-comprehensive.postman_collection.shipping.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.auth.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.catalog.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.checkout.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.gateway.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.inventory.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.kafka.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.media.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.payment.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.ratings.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.refund.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.shipping.json`: state n/a
- `run-auth.json`: state COMPLETED
- `run-catalog.json`: state COMPLETED
- `run-checkout.json`: state COMPLETED
- `run-gateway.json`: state COMPLETED
- `run-inventory.json`: state COMPLETED
- `run-kafka.json`: state COMPLETED
- `run-lifecycle.json`: state COMPLETED
- `run-media.json`: state COMPLETED
- `run-payment.json`: state COMPLETED
- `run-ratings.json`: state COMPLETED
- `run-refund.json`: state COMPLETED
- `run-shipping.json`: state COMPLETED
- `setup.json`: state READY

## Disclosure

The end-to-end environment is the repository docker compose stack: PostgreSQL 16,
Redis, Kafka in KRaft mode, Elasticsearch, Keycloak and RustFS are real containers.
The external payment provider is the repository mock provider, so the provider
boundary is simulated while every internal boundary is real.
