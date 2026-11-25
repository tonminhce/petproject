# End-to-End Verification

Generated: 2026-11-25T02:55:43Z

Run identifier: `run-20261118-a2b6e6fc`

## Artefacts

- `attempt-sequence.json`: state n/a
- `e2e-ledger.json`: state n/a
- `newman-all-1-petproject-comprehensive.postman_collection-1795571487579336000.json`: state n/a
- `newman-all-1-petproject-e2e-business-flow.postman_collection-1795571519271429000.json`: state n/a
- `report.json`: state FAILED
- `run-all-1.json`: state FAILED
- `run-all.json`: state FAILED
- `run-auth.json`: state COMPLETED
- `run-business.json`: state COMPLETED
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
- `teardown.json`: state TORN_DOWN

## Disclosure

The end-to-end environment is the repository docker compose stack: PostgreSQL 16,
Redis, Kafka in KRaft mode, Elasticsearch, Keycloak and RustFS are real containers.
The external payment provider is the repository mock provider, so the provider
boundary is simulated while every internal boundary is real.
