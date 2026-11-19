#!/usr/bin/env bash
#
# start-docker.sh - Build images and start the e-commerce microservices stack
#
# Workflow:
#   1. Verify Docker daemon is running
#   2. Verify .env file exists
#   3. Build all service images via `mvn jib:dockerBuild`
#   4. Start infrastructure (postgres, redis, kafka, elasticsearch, keycloak, rustfs)
#   5. Wait for infrastructure to become healthy
#   6. Start all 14 application services
#   7. Wait for gateway-service to become healthy
#   8. Print service URLs and follow-up commands
#
# Idempotent: safe to re-run; existing containers are recreated.

set -euo pipefail

# ============================================================
# ANSI colors (readonly; emit via printf)
# ============================================================
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly CYAN='\033[0;36m'
readonly BOLD='\033[1m'
readonly NC='\033[0m'

# ============================================================
# Constants
# ============================================================
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
readonly ENV_FILE="${SCRIPT_DIR}/.env"
readonly MVN="${SCRIPT_DIR}/mvnw"

readonly -a SERVICES=(
    "gateway-service" "auth-service" "product-service" "order-service"
    "payment-service" "shipping-service" "inventory-service"
    "favourite-service" "rating-service" "media-service"
    "tax-service" "promotion-service" "search-service" "notification-service"
)

readonly -a INFRA_SERVICES=(
    "postgres" "redis" "kafka" "elasticsearch" "keycloak" "rustfs"
)

readonly HEALTH_TIMEOUT=180
readonly HEALTH_INTERVAL=5
readonly MVN_BUILD_TIMEOUT=1800

# ============================================================
# Logging helpers
# ============================================================
log_info()    { printf '%b[i]%b %s\n' "${CYAN}"   "${NC}" "$*"; }
log_step()    { printf '%b[*]%b %s\n' "${BLUE}"   "${NC}" "$*"; }
log_success() { printf '%b[+]%b %s\n' "${GREEN}"  "${NC}" "$*"; }
log_warning() { printf '%b[!]%b %s\n' "${YELLOW}" "${NC}" "$*"; }
log_error()   { printf '%b[x]%b %s\n' "${RED}"    "${NC}" "$*" >&2; }

# ============================================================
# Trap: print message on unexpected exit
# ============================================================
on_error() {
    local exit_code=$?
    log_error "Script aborted (exit ${exit_code}). Some containers may still be starting."
    log_error "Inspect with: docker compose -f \"${COMPOSE_FILE}\" ps"
    exit "${exit_code}"
}
trap on_error ERR

# ============================================================
# Prerequisite checks
# ============================================================
check_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        log_error "docker CLI not found in PATH"
        exit 1
    fi
    if ! docker info >/dev/null 2>&1; then
        log_error "Docker daemon is not running. Start Docker Desktop and try again."
        exit 1
    fi
    # Verify Compose v2 plugin (not legacy docker-compose)
    if ! docker compose version >/dev/null 2>&1; then
        log_error "Docker Compose v2 plugin missing. Install Docker Desktop 4.13+ or compose-plugin."
        exit 1
    fi
    log_success "Docker daemon OK ($(docker compose version --short 2>/dev/null || echo unknown))"
}

check_env_file() {
    if [[ ! -f "${ENV_FILE}" ]]; then
        log_error ".env file not found at: ${ENV_FILE}"
        log_error "Copy from template or create one (see README)."
        exit 1
    fi
    log_success ".env file present"
}

check_compose_file() {
    if [[ ! -f "${COMPOSE_FILE}" ]]; then
        log_error "docker-compose.yml not found at: ${COMPOSE_FILE}"
        exit 1
    fi
}

check_maven() {
    if [[ ! -x "${MVN}" ]]; then
        log_error "Maven wrapper not found or not executable at: ${MVN}"
        exit 1
    fi
}

# ============================================================
# Build all 14 service images via Jib (idempotent - uses layers)
# ============================================================
build_images() {
    log_step "Building 14 service images with 'mvn jib:dockerBuild' (this may take several minutes)..."

    # Build from project root so Jib picks up all modules.
    # utils module has jib.skip=true so it won't produce one.
    cd "${SCRIPT_DIR}"

    # Note: `timeout` is GNU coreutils and not on macOS by default.
    # We rely on Maven's own internal timeouts for the build phase.
    if ! "${MVN}" -q -DskipTests jib:dockerBuild; then
        log_error "Image build failed. Run 'mvn jib:dockerBuild' manually for details."
        exit 1
    fi

    log_success "All service images built"
}

# ============================================================
# Start infrastructure first (idempotent: docker compose up -d
# will recreate existing containers)
# ============================================================
start_infrastructure() {
    log_step "Starting infrastructure: ${INFRA_SERVICES[*]}"

    cd "${SCRIPT_DIR}"
    if ! docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" \
        up -d "${INFRA_SERVICES[@]}"; then
        log_error "Failed to start infrastructure containers"
        exit 1
    fi
}

# ============================================================
# Wait until all listed services are healthy (or timeout)
# ============================================================
wait_for_healthy() {
    local label=$1
    shift
    local -a targets=("$@")

    log_step "Waiting for ${label} to become healthy (timeout: ${HEALTH_TIMEOUT}s)..."

    local elapsed=0
    local pending=1

    while (( elapsed < HEALTH_TIMEOUT )); do
        pending=0

        for svc in "${targets[@]}"; do
            # state can be: running, healthy, unhealthy, exited, ...
            local state
            state=$(docker inspect --format='{{.State.Health.Status}}' "${svc}" 2>/dev/null || echo "missing")
            if [[ "${state}" != "healthy" ]]; then
                pending=$((pending + 1))
            fi
        done

        if (( pending == 0 )); then
            log_success "${label} are healthy"
            return 0
        fi

        printf '.'
        sleep "${HEALTH_INTERVAL}"
        elapsed=$((elapsed + HEALTH_INTERVAL))
    done

    printf '\n'
    log_warning "${label} did not become healthy within ${HEALTH_TIMEOUT}s"
    log_warning "Continuing anyway - check: docker compose -f \"${COMPOSE_FILE}\" ps"
    return 1
}

# ============================================================
# Start all 14 application services
# ============================================================
start_services() {
    log_step "Starting application services: ${SERVICES[*]}"

    cd "${SCRIPT_DIR}"
    if ! docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" \
        up -d "${SERVICES[@]}"; then
        log_error "Failed to start application services"
        exit 1
    fi
}

# ============================================================
# Print final summary: URLs and follow-up commands
# ============================================================
print_summary() {
    local gateway_url="http://localhost:8080"
    local keycloak_url="http://localhost:8080"

    printf '\n'
    printf '%b╔════════════════════════════════════════════════════════════════╗%b\n' "${GREEN}" "${NC}"
    printf '%b║           E-commerce Stack is UP                                ║%b\n' "${GREEN}" "${NC}"
    printf '%b╚════════════════════════════════════════════════════════════════╝%b\n' "${GREEN}" "${NC}"
    printf '\n'

    printf '%bService URLs:%b\n' "${BOLD}" "${NC}"
    printf '  %bGateway%b             %s\n' "${CYAN}" "${NC}" "${gateway_url}"
    printf '  %bKeycloak Admin%b      %s  (admin / admin)\n' "${CYAN}" "${NC}" "${keycloak_url}"
    printf '  %bPostgreSQL%b          localhost:5432  (admin / admin)\n' "${CYAN}" "${NC}"
    printf '  %bRedis%b               localhost:6379  (password: admin)\n' "${CYAN}" "${NC}"
    printf '  %bKafka%b               localhost:9092\n' "${CYAN}" "${NC}"
    printf '  %bElasticsearch%b       localhost:9200\n' "${CYAN}" "${NC}"
    printf '  %bRustFS (S3 API)%b     localhost:9000  (admin / admin)\n' "${CYAN}" "${NC}"
    printf '  %bRustFS Console%b      localhost:9001\n' "${CYAN}" "${NC}"
    printf '\n'

    printf '%bBackend services:%b\n' "${BOLD}" "${NC}"
    printf '  auth-service         :8088  | product-service    :8086\n'
    printf '  order-service        :8084  | payment-service    :8085\n'
    printf '  shipping-service     :8087  | inventory-service  :8082\n'
    printf '  favourite-service    :8081  | rating-service     :8089\n'
    printf '  tax-service          :8091  | promotion-service  :8093\n'
    printf '  search-service       :8094  | notification-service :8090\n'
    printf '\n'
    printf '%bIngress-only (via gateway :8080, no host port):%b media-service\n' "${CYAN}" "${NC}"
    printf '\n'

    printf '%bFollow-up commands:%b\n' "${BOLD}" "${NC}"
    printf '  docker compose -f docker-compose.yml ps               # status\n'
    printf '  docker compose -f docker-compose.yml logs -f [svc]    # logs\n'
    printf '  docker compose -f docker-compose.yml restart <svc>    # restart one\n'
    printf '  ./stop-docker.sh                                       # stop (keep data)\n'
    printf '  ./stop-docker.sh --volumes                             # stop + WIPE data\n'
    printf '\n'
}

# ============================================================
# Main
# ============================================================
main() {
    printf '%b============================================================%b\n' "${BOLD}" "${NC}"
    printf '%b E-commerce Microservices - Docker Stack Starter%b\n' "${BOLD}" "${NC}"
    printf '%b============================================================%b\n' "${BOLD}" "${NC}"
    printf '\n'

    log_step "Phase 1/5 - Prerequisite checks"
    check_docker
    check_env_file
    check_compose_file
    check_maven

    log_step "Phase 2/5 - Build images"
    build_images

    log_step "Phase 3/5 - Start infrastructure"
    start_infrastructure
    wait_for_healthy "Infrastructure" "${INFRA_SERVICES[@]}" || true

    log_step "Phase 4/5 - Start application services"
    start_services
    wait_for_healthy "Gateway" "gateway-service" || true

    log_step "Phase 5/5 - Summary"
    print_summary

    log_success "Done."
}

main "$@"