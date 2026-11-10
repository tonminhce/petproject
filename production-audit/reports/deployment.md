# Deployment & Operations

Generated: 2026-11-10T10:05:42

Findings in this category: **1**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-036 | P3 | OPEN | .env.prod.example omits CORS_ALLOWED_ORIGIN_PATTERNS, which the gateway requires to start |

---

### F-036 - .env.prod.example omits CORS_ALLOWED_ORIGIN_PATTERNS, which the gateway requires to start

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** gateway-service, deployment
- **Source files:**
  - `.env.prod.example`
  - `gateway-service/src/main/resources/application.yml`
  - `docker-compose.prod.yml`
- **Actual implementation:** The gateway resolves gateway.cors-allowed-origin-patterns from ${CORS_ALLOWED_ORIGIN_PATTERNS:?CORS_ALLOWED_ORIGIN_PATTERNS must be set explicitly}. docker-compose.yml supplies it with a required placeholder, and docker-compose.prod.yml inherits that mapping, but .env.prod.example never defines the variable.
- **Expected:** Every variable the production stack requires is present in the production environment template.
- **Trigger:** An operator provisions from .env.prod.example.
- **Root cause:** The template and the required-placeholder set drifted apart.
- **Consequences:** The gateway container fails to start with an unresolved-placeholder error - a fail-fast behaviour, but one that surfaces as an outage during deployment rather than as a configuration review finding.
- **Required remediation:** Add CORS_ALLOWED_ORIGIN_PATTERNS (and any other required variable) to .env.prod.example with an explicit non-wildcard value.
- **Regression test:** CI check: render the prod compose configuration and assert every required placeholder resolves.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
