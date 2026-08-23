#!/usr/bin/env bash
#
# stop-docker.sh - Stop the e-commerce microservices stack
#
# Usage:
#   ./stop-docker.sh                  Stop containers, KEEP volumes and images
#   ./stop-docker.sh --volumes        Stop containers, REMOVE named volumes (data loss!)
#   ./stop-docker.sh -v               Alias for --volumes
#   ./stop-docker.sh --images         Also remove built service images
#
# Default is non-destructive: containers stop but volumes persist.

set -euo pipefail

# ============================================================
# Colors
# ============================================================
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly CYAN='\033[0;36m'
readonly BOLD='\033[1m'
readonly NC='\033[0m'

# ============================================================
# Constants
# ============================================================
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
readonly ENV_FILE="${SCRIPT_DIR}/.env"

# Flag state
REMOVE_VOLUMES=0
REMOVE_IMAGES=0

# ============================================================
# Logging
# ============================================================
log_info()    { printf '%b[i]%b %s\n' "${CYAN}"   "${NC}" "$*"; }
log_step()    { printf '%b[*]%b %s\n' "${BOLD}"   "${NC}" "$*"; }
log_success() { printf '%b[+]%b %s\n' "${GREEN}"  "${NC}" "$*"; }
log_warning() { printf '%b[!]%b %s\n' "${YELLOW}" "${NC}" "$*"; }
log_error()   { printf '%b[x]%b %s\n' "${RED}"    "${NC}" "$*" >&2; }

# ============================================================
# Usage
# ============================================================
usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Stop the e-commerce microservices stack (docker compose down).

Options:
  -v, --volumes    Also remove named volumes (DELETES data: postgres,
                   redis, kafka, elasticsearch, rustfs)
      --images     Also remove built service images (forces re-build on next start)
  -h, --help       Show this help

Default: stop containers only, keep volumes and images (safe).

Examples:
  $(basename "$0")                  # Safe stop (keep data)
  $(basename "$0") --volumes        # Wipe volumes - destructive!
  $(basename "$0") -v --images      # Full cleanup
EOF
}

# ============================================================
# Argument parsing
# ============================================================
parse_args() {
    while (($#)); do
        case "$1" in
            -v|--volumes)
                REMOVE_VOLUMES=1
                shift
                ;;
            --images)
                REMOVE_IMAGES=1
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            --)
                shift
                break
                ;;
            -*)
                log_error "Unknown option: $1"
                usage
                exit 2
                ;;
            *)
                log_error "Unexpected argument: $1"
                usage
                exit 2
                ;;
        esac
    done
}

# ============================================================
# Confirmation prompt (skipped if non-interactive)
# ============================================================
confirm() {
    local prompt=$1
    local reply

    # Non-interactive (CI) → assume NO unless FORCE=1
    if [[ ! -t 0 ]]; then
        if [[ "${FORCE:-0}" == "1" ]]; then
            log_warning "Non-interactive + FORCE=1 → proceeding with '${prompt}'"
            return 0
        fi
        log_error "Refusing destructive action in non-interactive mode. Re-run with FORCE=1."
        exit 1
    fi

    read -r -p "$(printf '%b%s [y/N]: %b' "${YELLOW}" "${prompt}" "${NC}")" reply
    case "${reply}" in
        [yY]|[yY][eE][sS]) return 0 ;;
        *)                 return 1 ;;
    esac
}

# ============================================================
# docker compose down wrapper
# ============================================================
stop_stack() {
    if [[ ! -f "${COMPOSE_FILE}" ]]; then
        log_error "docker-compose.yml not found at: ${COMPOSE_FILE}"
        exit 1
    fi

    cd "${SCRIPT_DIR}"

    local -a down_args=(down)
    if (( REMOVE_VOLUMES )); then
        down_args+=(--volumes)
    fi
    if (( REMOVE_IMAGES )); then
        down_args+=(--rmi all)
    fi

    log_step "Running: docker compose ${down_args[*]}"
    if docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "${down_args[@]}"; then
        log_success "Stack stopped"
    else
        log_error "docker compose down failed"
        exit 1
    fi
}

# ============================================================
# Remove service images (only if --images requested and stack
# didn't already remove them)
# ============================================================
remove_images_if_requested() {
    if (( ! REMOVE_IMAGES )); then
        return 0
    fi

    local -a images=(
        "gateway-service" "auth-service" "product-service" "order-service"
        "payment-service" "shipping-service" "inventory-service"
        "favourite-service" "rating-service" "media-service"
        "tax-service" "promotion-service" "search-service" "notification-service"
    )

    log_step "Removing built service images..."
    # docker rmi is tolerant of missing ones; ignore failures
    docker rmi "${images[@]}" >/dev/null 2>&1 || true
    log_success "Service images removed (or already gone)"
}

# ============================================================
# Main
# ============================================================
main() {
    parse_args "$@"

    printf '%b============================================================%b\n' "${BOLD}" "${NC}"
    printf '%b E-commerce Microservices - Docker Stack Stopper%b\n' "${BOLD}" "${NC}"
    printf '%b============================================================%b\n' "${BOLD}" "${NC}"
    printf '\n'

    if (( REMOVE_VOLUMES )); then
        log_warning "--volumes requested: Postgres, Redis, Kafka, Elasticsearch,"
        log_warning "and RustFS data will be PERMANENTLY DELETED."
        if ! confirm "Delete all volumes?"; then
            log_info "Aborted by user"
            exit 0
        fi
    fi

    if (( REMOVE_IMAGES )); then
        log_warning "--images requested: All 14 built service images will be removed."
        log_warning "Next start will rebuild them (~minutes)."
        if ! confirm "Remove service images?"; then
            log_info "Aborted by user"
            exit 0
        fi
    fi

    stop_stack
    remove_images_if_requested

    printf '\n'
    if (( REMOVE_VOLUMES || REMOVE_IMAGES )); then
        log_success "Stack stopped and resources cleaned."
    else
        log_success "Stack stopped. Volumes and images preserved."
        log_info "Restart with: ./start-docker.sh"
    fi
}

main "$@"