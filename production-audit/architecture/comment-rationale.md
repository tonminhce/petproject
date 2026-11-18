# Archived Source Rationale

Design rationale removed from first-party source under the no-comment policy.
Each entry records the original location and the exact removed text.

## start-docker.sh

```text
#
```

```text
# start-docker.sh - Build images and start the e-commerce microservices stack
```

```text
#
```

```text
# Workflow:
```

```text
#   1. Verify Docker daemon is running
```

```text
#   2. Verify .env file exists
```

```text
#   3. Build all service images via `mvn jib:dockerBuild`
```

```text
#   4. Start infrastructure (postgres, redis, kafka, elasticsearch, keycloak, rustfs)
```

```text
#   5. Wait for infrastructure to become healthy
```

```text
#   6. Start all 14 application services
```

```text
#   7. Wait for gateway-service to become healthy
```

```text
#   8. Print service URLs and follow-up commands
```

```text
#
```

```text
# Idempotent: safe to re-run; existing containers are recreated.
```

```text
# ============================================================
```

```text
# ANSI colors (readonly; emit via printf)
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Constants
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Logging helpers
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Trap: print message on unexpected exit
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Prerequisite checks
```

```text
# ============================================================
```

```text
# Verify Compose v2 plugin (not legacy docker-compose)
```

```text
# ============================================================
```

```text
# Build all 14 service images via Jib (idempotent - uses layers)
```

```text
# ============================================================
```

```text
# Build from project root so Jib picks up all modules.
```

```text
# utils module has jib.skip=true so it won't produce one.
```

```text
# Note: `timeout` is GNU coreutils and not on macOS by default.
```

```text
# We rely on Maven's own internal timeouts for the build phase.
```

```text
# ============================================================
```

```text
# Start infrastructure first (idempotent: docker compose up -d
```

```text
# will recreate existing containers)
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Wait until all listed services are healthy (or timeout)
```

```text
# ============================================================
```

```text
# state can be: running, healthy, unhealthy, exited, ...
```

```text
# ============================================================
```

```text
# Start all 14 application services
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Print final summary: URLs and follow-up commands
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Main
```

```text
# ============================================================
```

## stop-docker.sh

```text
#
```

```text
# stop-docker.sh - Stop the e-commerce microservices stack
```

```text
#
```

```text
# Usage:
```

```text
#   ./stop-docker.sh                  Stop containers, KEEP volumes and images
```

```text
#   ./stop-docker.sh --volumes        Stop containers, REMOVE named volumes (data loss!)
```

```text
#   ./stop-docker.sh -v               Alias for --volumes
```

```text
#   ./stop-docker.sh --images         Also remove built service images
```

```text
#
```

```text
# Default is non-destructive: containers stop but volumes persist.
```

```text
# ============================================================
```

```text
# Colors
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Constants
```

```text
# ============================================================
```

```text
# Flag state
```

```text
# ============================================================
```

```text
# Logging
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Usage
```

```text
# ============================================================
```

```text
# Safe stop (keep data)
```

```text
# Wipe volumes - destructive!
```

```text
# Full cleanup
```

```text
# ============================================================
```

```text
# Argument parsing
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Confirmation prompt (skipped if non-interactive)
```

```text
# ============================================================
```

```text
# Non-interactive (CI) → assume NO unless FORCE=1
```

```text
# ============================================================
```

```text
# docker compose down wrapper
```

```text
# ============================================================
```

```text
# ============================================================
```

```text
# Remove service images (only if --images requested and stack
```

```text
# didn't already remove them)
```

```text
# ============================================================
```

```text
# docker rmi is tolerant of missing ones; ignore failures
```

```text
# ============================================================
```

```text
# Main
```

```text
# ============================================================
```

## scripts/run-sonar-scan.sh

```text
# shellcheck disable=SC1090
```

## scripts/setup-sonar-project.sh

```text
# Determine working credentials
```

```text
# shellcheck disable=SC1090
```

```text
# Check if existing token works
```

```text
# Try new password first
```

```text
# 2. Create project if it doesn't exist
```

```text
# 3. Generate token if needed
```

```text
# 4. Save to .env.sonar
```

```text
# 5. Quality gate setup
```

```text
# Add strict conditions (Sonar Way + Strict)
```

```text
# Assign to project
```
