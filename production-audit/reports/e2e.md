# End-to-End Verification

Generated: 2026-11-18T16:42:55Z

Run identifier: `run-20261118-a2b6e6fc`

## Artefacts

- `petproject-comprehensive.postman_collection.json`: state n/a
- `petproject-e2e-business-flow.postman_collection.json`: state n/a
- `run-lifecycle.json`: state FAILED
- `setup.json`: state READY

## Disclosure

The end-to-end environment is the repository docker compose stack: PostgreSQL 16,
Redis, Kafka in KRaft mode, Elasticsearch, Keycloak and RustFS are real containers.
The external payment provider is the repository mock provider, so the provider
boundary is simulated while every internal boundary is real.
